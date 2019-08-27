package cn.graiph.blobfs

import java.io._
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.{CRC32, CheckedInputStream}
import java.util.{Properties, Random}

import cn.graiph.blobfs.util.{Configuration, ConfigurationEx, Logging}
import com.google.gson.Gson
import net.neoremind.kraps.RpcConf
import net.neoremind.kraps.rpc.netty.NettyRpcEnvFactory
import net.neoremind.kraps.rpc.{RpcCallContext, RpcEndpoint, RpcEnv, RpcEnvServerConfig}
import org.apache.commons.io.IOUtils
import org.apache.zookeeper.ZooDefs.Ids
import org.apache.zookeeper.{CreateMode, WatchedEvent, Watcher, ZooKeeper}

import scala.collection.mutable.ArrayBuffer
import scala.collection.{JavaConversions, mutable}

class BlobFsServersException(msg: String, cause: Throwable = null) extends RuntimeException(msg, cause) {
}

/**
  * Created by bluejoe on 2019/8/22.
  */
object FsNodeServer {
  def build(configFile: File): FsNodeServer = {
    val props = new Properties();
    val fis = new FileInputStream(configFile);
    props.load(fis);
    fis.close();

    val conf = new ConfigurationEx(new Configuration {
      override def getRaw(name: String): Option[String] =
        if (props.containsKey(name))
          Some(props.getProperty(name))
        else
          None;
    })

    val storeDir = conf.getRequiredValueAsFile("data.storeDir", configFile.getParentFile);
    if (!storeDir.exists())
      throw new BlobFsServersException(s"store dir does not exist: ${storeDir.getCanonicalFile.getAbsolutePath}");

    //val nodeId = conf.getRequiredValueAsInt("data.nodeId");
    val regions = conf.getRequiredValueAsString("data.regions").split(",").map(_.toInt);

    new FsNodeServer(
      conf.getRequiredValueAsString("zookeeper.address"),
      regions,
      storeDir,
      conf.getValueAsString("server.host", "localhost"),
      conf.getValueAsInt("server.port", 1224),
      FileWriteCursorIO.loadCursors(storeDir, regions).values.toArray
    )
  }
}

class FsNodeServer(zks: String, regions: Array[Int], storeDir: File, host: String, port: Int, cursors: Array[FileWriteCursor]) {
  var rpcServer: FsRpcServer = null;

  def start() {
    val zk = new ZooKeeper(zks, 2000, new Watcher {
      override def process(event: WatchedEvent): Unit = {
      }
    });

    if (zk.exists("/blobfs", false) == null)
      zk.create("/blobfs", "".getBytes, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

    if (zk.exists("/blobfs/nodes", false) == null)
      zk.create("/blobfs/nodes", "".getBytes, Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);

    val json = new Gson().toJson(JavaConversions.mapAsJavaMap(
      Map("regions" -> regions, "rpcAddress" -> s"$host:$port"))).getBytes;

    val nodeId = s"${host}_${port}";
    zk.create(s"/blobfs/nodes/$nodeId", json, Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);

    rpcServer = new FsRpcServer(storeDir, host, port, cursors);
    rpcServer.start();
  }

  def shutdown(): Unit = {
    if (rpcServer != null)
      rpcServer.shutdown();
  }
}

object FileWriteCursorIO {
  def loadCursors(storeDir: File, regions: Array[Int]): Map[Int, FileWriteCursor] = {
    regions.map((region: Int) => {
      val regionDir = new File(storeDir, "" + region);
      val cursorFile = new File(regionDir, "/cursor");
      val values: (Int, Int, Long) = {
        if (cursorFile.exists()) {
          val dis = new DataInputStream(new FileInputStream(cursorFile));
          val v1 = dis.readInt()
          val v2 = dis.readInt()
          val v3 = dis.readLong()
          dis.close()
          (v1, v2, v3)
        }
        else {
          (0, 0, 0)
        }
      }

      region -> FileWriteCursor(region, regionDir, values._1, values._2, values._3)
    }).toMap
  }

  def save(storeDir: File, cursor: FileWriteCursor): Unit = {
    val cursorFile = new File(storeDir, "cursor");
    val dos = new DataOutputStream(new FileOutputStream(cursorFile));
    dos.writeInt(cursor.sequenceId)
    dos.writeInt(cursor.subId)
    dos.writeLong(cursor.offset);
    dos.close
  }
}

case class FileWriteCursor(rangeId: Int, val storeDir: File,
                           var sequenceId: Int, var subId: Int, var offset: Long) {

  class Output {
    var bodyPtr: FileOutputStream = _;
    var metaPtr: DataOutputStream = _;

    private def writeMeta(length: Long, crc32: Long): Unit = {
      //each entry uses 30bytes
      //[ssss][oooo][oooo][llll][llll][cccc][cccc][__]
      metaPtr.writeInt(subId);
      metaPtr.writeLong(offset);
      metaPtr.writeLong(length);
      metaPtr.writeLong(crc32);
      metaPtr.write((0 to 1).map(_ => 0.toByte).toArray);
    }

    private def writeFileBody(src: File): Int = {
      //TOOD: optimize writing
      val is = new FileInputStream(src);
      var n = 0;
      var lengthWithPadding = 0;
      while (n >= 0) {
        //10K?
        val bytes = new Array[Byte](10240);
        n = is.read(bytes);
        if (n > 0) {
          bodyPtr.write(bytes);
          lengthWithPadding += bytes.length;
        }
      }

      lengthWithPadding
    }

    def write(src: File, length: Int): Int = {
      if (bodyPtr == null) {
        refresh();
      }

      val crc32Value = computeCrc32(src)
      val lengthWithPadding = writeFileBody(src)
      writeMeta(length, crc32Value);
      lengthWithPadding;
    }

    def refresh() = {
      if (bodyPtr != null)
        bodyPtr.close();
      if (metaPtr != null)
        metaPtr.close();

      val fullId = makeFileId().asHexString();
      //0001000000000000000000000300e90001
      //[nn][nn][mm][mm][__][__][ss][ss][ss][ss][ss][ss][ss][ss][oo][oo]
      val fileId = fullId.substring(0, 4) +
        "/" + fullId.substring(15, 18) +
        "/" + fullId.substring(18, 21) +
        "/" + fullId.substring(21, 24) +
        "/" + fullId.substring(24, 28);

      val body = new File(storeDir, fileId)
      body.getParentFile.mkdirs();
      if (!body.exists())
        body.createNewFile();

      bodyPtr = new FileOutputStream(body, true); //append only
      metaPtr = new DataOutputStream(new FileOutputStream(new File(storeDir, fileId + ".meta"), true));
    }
  }

  var output: Output = new Output();

  private def makeFileId() = {
    FileId.make(rangeId, 0, sequenceId, subId);
  }

  private def moveNext(length: Int) = {
    subId += 1;
    offset += length;

    if (subId >= 1000) {
      //close file first
      sequenceId += 1;
      subId = 0;
      offset = 0;

      //open new files
      output.refresh();
    }
  }

  def save(src: File, length: Int): FileId = {
    //TODO: transaction assurance
    val lengthWithPadding = output.write(src, length);

    //TODO: to be optimized
    FileWriteCursorIO.save(storeDir, this);
    src.delete();
    moveNext(lengthWithPadding);
    makeFileId();
  }

  def computeCrc32(src: File): Long = {
    //get crc32
    val crc32 = new CRC32();
    val cis = new CheckedInputStream(new FileInputStream(src), crc32);
    while (cis.read() != -1) {
    }
    val crc32Value = crc32.getValue;
    cis.close();
    crc32Value
  }
}

class FsRpcServer(storeDir: File, host: String, port: Int, cursors: Array[FileWriteCursor])
  extends Logging {
  var rpcEnv: RpcEnv = null;

  def start() {
    logger.debug(s"cursors: $cursors");
    logger.debug(s"storeDir: ${storeDir.getCanonicalFile.getAbsolutePath}")
    logger.debug(s"served on $host:$port");

    val config = RpcEnvServerConfig(new RpcConf(), "blobfs-server", host, port);
    rpcEnv = NettyRpcEnvFactory.create(config);
    val endpoint: RpcEndpoint = new FileRpcEndpoint(rpcEnv, storeDir);
    rpcEnv.setupEndpoint("blobfs-service", endpoint);
    rpcEnv.awaitTermination();
  }

  def shutdown(): Unit = {
    if (rpcEnv != null)
      rpcEnv.shutdown();
  }

  class FileRpcEndpoint(override val rpcEnv: RpcEnv, storeDir: File)
    extends RpcEndpoint with Logging {
    val queue = new FileTaskQueue(storeDir);

    override def onStart(): Unit = {
      println("start endpoint")
    }

    override def receiveAndReply(context: RpcCallContext): PartialFunction[Any, Unit] = {
      case SendCompleteFileRequest(block: Array[Byte], totalLength: Long) => {
        logger.debug(s"received: ${SendCompleteFileRequest(block, totalLength)}");
        val opt = new queue.FileTask(totalLength).write(block, 0, totalLength.toInt, 0);
        context.reply(SendCompleteFileResponse(opt.get));
      }
      case StartSendChunksRequest(totalLength: Long) => {
        val transId = queue.create(totalLength);
        context.reply(StartSendChunksResponse(transId));
      }
      case SendChunkRequest(transId: Int, block: Array[Byte], offset: Long, blockLength: Int, blockIndex: Int) => {
        logger.debug(s"received: ${SendChunkRequest(transId: Int, block: Array[Byte], offset: Long, blockLength: Int, blockIndex: Int)}");
        val opt = queue.get(transId).write(block, offset, blockLength, blockIndex);
        opt.foreach(_ => queue.remove(transId))
        context.reply(blockIndex -> opt);
      }

    }

    override def onStop(): Unit = {
      println("stop endpoint")
    }
  }

  class FileTaskQueue(storeDir: File) extends Logging {
    val transactionalTasks = mutable.Map[Int, FileTask]();
    val transactionIdGen = new AtomicInteger();
    val rand = new Random();

    def create(totalLength: Long): Int = {
      val task = new FileTask(totalLength);
      val transId = transactionIdGen.incrementAndGet();
      transactionalTasks += transId -> task;
      transId;
    }

    def remove(uuid: Int) = transactionalTasks.remove(uuid)

    def get(transId: Int) = {
      transactionalTasks(transId);
    }

    class FileTask(totalLength: Long) {
      val cursor = cursors(rand.nextInt(cursors.size));

      case class Chunk(file: File, length: Int, index: Int) {
      }

      //create a new file
      val chunks = ArrayBuffer[Chunk]();
      var actualBytesWritten = 0;

      private def combine(chunks: Array[Chunk]): File = {
        if (chunks.length == 1) {
          chunks(0).file;
        }
        else {
          val tmpFile = File.createTempFile("blobfs-", "");
          val fos: FileOutputStream = new FileOutputStream(tmpFile, true);
          chunks.sortBy(_.index).foreach { chunk =>
            val cis = new FileInputStream(chunk.file);
            IOUtils.copy(cis, fos);
            cis.close();
            chunk.file.delete();
          }

          fos.close();
          tmpFile;
        }
      }

      def write(block: Array[Byte], offset: Long, blockLength: Int, blockIndex: Int): Option[FileId] = {
        logger.debug(s"writing block: $blockIndex");

        val tmpFile = File.createTempFile("blobfs-", ".chunk");
        val fos: FileOutputStream = new FileOutputStream(tmpFile);
        IOUtils.copy(new ByteArrayInputStream(block.slice(0, blockLength)), fos);
        fos.close();

        chunks += Chunk(tmpFile, blockLength, blockIndex);
        actualBytesWritten += blockLength;

        //end of file?
        if (actualBytesWritten >= totalLength) {
          Some(cursor.save(combine(chunks.toArray), actualBytesWritten))
        }
        else {
          None
        }
      }
    }

  }

}

