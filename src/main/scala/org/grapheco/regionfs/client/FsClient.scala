package org.grapheco.regionfs.client

import java.io.InputStream
import java.nio.ByteBuffer

import io.netty.buffer.ByteBuf
import net.neoremind.kraps.RpcConf
import net.neoremind.kraps.rpc.netty.{HippoEndpointRef, HippoRpcEnv, HippoRpcEnvFactory}
import net.neoremind.kraps.rpc.{RpcAddress, RpcEnvClientConfig}
import org.grapheco.commons.util.Logging
import org.grapheco.regionfs._
import org.grapheco.regionfs.util._

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

/**
  * a client to regionfs servers
  */
class FsClient(zks: String) extends Logging {
  val zookeeper = ZooKeeperClient.create(zks)
  val globalSetting = zookeeper.loadGlobalSetting()
  val clientFactory = new FsNodeClientFactory(globalSetting);

  //get all nodes
  val cachedClients = mutable.Map[Int, FsNodeClient]()
  val allNodes = mutable.Map[Int, RpcAddress]()
  val ring = new Ring[Int]()
  val nodesWatcher = zookeeper.watchNodeList(
    new ParsedChildNodeEventHandler[(Int, RpcAddress)] {
      override def onCreated(t: (Int, RpcAddress)): Unit = {
        allNodes += t
        ring += t._1
      }

      override def onDeleted(t: (Int, RpcAddress)): Unit = {
        allNodes -= t._1
        ring -= t._1
      }

      override def accepts(t: (Int, RpcAddress)): Boolean = {
        true
      }
    })

  //get all regions
  //32768->(1,2), 32769->(1), ...
  val allRegionWithNodes = ArrayBuffer[(Long, Int)]()
  val allRegionsWithListOfNode = mutable.Map[Long, ArrayBuffer[Int]]()

  val regionsWatcher = zookeeper.watchRegionList(
    new ParsedChildNodeEventHandler[(Long, Int)] {
      override def onCreated(t: (Long, Int)): Unit = {
        allRegionWithNodes += t
        allRegionsWithListOfNode.getOrElseUpdate(t._1, ArrayBuffer()) += t._2
      }

      override def onDeleted(t: (Long, Int)): Unit = {
        allRegionWithNodes -= t
        allRegionsWithListOfNode -= t._1
      }

      override def accepts(t: (Long, Int)): Boolean = {
        true
      }
    })

  val writerSelector = new RoundRobinSelector(ring);

  protected def clientOf(nodeId: Int): FsNodeClient = {
    cachedClients.getOrElseUpdate(nodeId,
      clientFactory.of(allNodes(nodeId)))
  }

  private def assertNodesNotEmpty() {
    if (allNodes.isEmpty) {
      if (logger.isTraceEnabled) {
        logger.trace(zookeeper.readNodeList().mkString(","))
      }

      throw new RegionFsClientException("no serving data nodes")
    }
  }

  def writeFile(content: ByteBuffer): Future[FileId] = {
    val client = getWriterClient
    val (regionId: Long, fileId: FileId, nodes: Array[Int]) =
      Await.result(client.prepareToWriteFile(content), Duration("1s"))

    val crc32 =
      if (globalSetting.enableCrc) {
        CrcUtils.computeCrc32(content.duplicate())
      }
      else {
        0
      }

    val futures =
      nodes.map(clientOf(_).writeFile(regionId, crc32, fileId, content.duplicate()))

    Future {
      //TODO: consistency check
      futures.map(x => Await.result(x, Duration.Inf)).head
    }
  }

  def getWriterClient: FsNodeClient = {
    assertNodesNotEmpty();
    clientOf(writerSelector.select())
  }

  def readFile[T](fileId: FileId, rpcTimeout: Duration): InputStream = {
    val client = getReaderClient(fileId)
    client.readFile(fileId, rpcTimeout)
  }

  private def getReaderClient[T](fileId: FileId): FsNodeClient = {
    assertNodesNotEmpty()

    val rwlon = allRegionsWithListOfNode.get(fileId.regionId)
    if (rwlon.isEmpty)
      throw new WrongFileIdException(fileId);

    val map = rwlon.get.map((_, 1)).toMap
    val maybeNodeId = ring.!(map.contains(_))
    if (maybeNodeId.isEmpty)
      throw new WrongFileIdException(fileId);

    clientOf(maybeNodeId.get);
  }

  def deleteFile[T](fileId: FileId): Future[Boolean] = {
    val futures = allRegionsWithListOfNode(fileId.regionId).map(clientOf(_).deleteFile(fileId))

    if (futures.isEmpty)
      throw new WrongFileIdException(fileId);

    Future {
      futures.map(Await.result(_, Duration.Inf)).head
    }
  }

  def close = {
    nodesWatcher.close()
    regionsWatcher.close()
    clientFactory.close()
    zookeeper.close()
  }
}

/**
  * FsNodeClient factory
  */
class FsNodeClientFactory(globalSetting: GlobalSetting) {
  val refs = mutable.Map[RpcAddress, HippoEndpointRef]();

  val rpcEnv: HippoRpcEnv = {
    val rpcConf = new RpcConf()
    val config = RpcEnvClientConfig(rpcConf, "regionfs-client")
    HippoRpcEnvFactory.create(config)
  }

  def of(remoteAddress: RpcAddress): FsNodeClient = {
    val endPointRef = rpcEnv.synchronized {
      refs.getOrElseUpdate(remoteAddress,
        rpcEnv.setupEndpointRef(RpcAddress(remoteAddress.host, remoteAddress.port), "regionfs-service"));
    }

    new FsNodeClient(globalSetting: GlobalSetting, endPointRef, remoteAddress)
  }

  def of(remoteAddress: String): FsNodeClient = {
    val pair = remoteAddress.split(":")
    of(RpcAddress(pair(0), pair(1).toInt))
  }

  def close(): Unit = {
    refs.foreach(x => rpcEnv.stop(x._2))
    refs.clear()
    rpcEnv.shutdown()
  }
}

/**
  * an FsNodeClient is an underline client used by FsClient
  * it sends raw messages (e.g. SendCompleteFileRequest) to NodeServer and handles responses
  */
class FsNodeClient(globalSetting: GlobalSetting, val endPointRef: HippoEndpointRef, val remoteAddress: RpcAddress) extends Logging {
  private def safeCall[T](body: => T): T = {
    try {
      body
    }
    catch {
      case e: Throwable => throw new ServerRaisedException(e)
    }
  }

  def prepareToWriteFile(content: ByteBuffer): Future[(Long, FileId, Array[Int])] = {
    safeCall {
      endPointRef.ask[PrepareToWriteFileResponse](
        PrepareToWriteFileRequest(content.remaining())).map(x => (x.regionId, x.fileId, x.nodes))
    }
  }

  def writeFile(regionId: Long, crc32: Long, fileId: FileId, content: ByteBuffer): Future[FileId] = {
    safeCall {
      endPointRef.askWithStream[SendFileResponse](
        SendFileRequest(regionId, fileId, content.remaining(), crc32),
        (buf: ByteBuf) => {
          buf.writeBytes(content)
        }).map(_.fileId)
    }
  }

  def deleteFile(fileId: FileId): Future[Boolean] = {
    safeCall {
      endPointRef.ask[DeleteFileResponse](
        DeleteFileRequest(fileId.regionId, fileId.localId)).map(_.success)
    }
  }

  def readFile[T](fileId: FileId, rpcTimeout: Duration): InputStream = {
    safeCall {
      endPointRef.getInputStream(
        ReadFileRequest(fileId.regionId, fileId.localId),
        rpcTimeout)
    }
  }
}

trait FsNodeSelector {
  def select(): Int;
}

class RoundRobinSelector(nodes: Ring[Int]) extends FsNodeSelector {
  def select(): Int = {
    nodes !
  }
}

class RegionFsClientException(msg: String, cause: Throwable = null)
  extends RegionFsException(msg, cause) {

}

class WrongFileIdException(fileId: FileId) extends
  RegionFsClientException(s"wrong fileid: $fileId") {

}

class ServerRaisedException(cause: Throwable) extends
  RegionFsClientException(s"got a server side error: ${cause.getMessage}") {

}