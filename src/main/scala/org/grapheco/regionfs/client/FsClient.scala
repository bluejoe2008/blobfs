package org.grapheco.regionfs.client

import java.io.InputStream
import java.nio.ByteBuffer

import io.netty.buffer.ByteBuf
import net.neoremind.kraps.RpcConf
import net.neoremind.kraps.rpc.netty.{HippoEndpointRef, HippoRpcEnv, HippoRpcEnvFactory}
import net.neoremind.kraps.rpc.{RpcAddress, RpcEnvClientConfig}
import org.grapheco.commons.util.Logging
import org.grapheco.regionfs._
import org.grapheco.regionfs.util.CrcUtils

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.Duration

/**
  * a client to regionfs servers
  */
class FsClient(zks: String) extends Logging {
  val zookeeper = ZooKeeperClient.create(zks)
  val globalConfig = zookeeper.loadGlobalConfig()
  val clientFactory = new FsNodeClientFactory(globalConfig);

  //get all nodes
  val allNodeWithClients = mutable.Map[Int, FsNodeClient]()
  val ring = new Ring[Int]()
  val nodesWatcher = new NodeWatcher(zookeeper)
    with ChildNodePathAware[(Int, RpcAddress)] {
    override def onChanged(batch: Iterable[(Int, RpcAddress)]): Unit = {
      allNodeWithClients.clear()
      allNodeWithClients ++= batch.map(t => t._1 -> (clientFactory.of(t._2)))

      ring.clear()
      ring ++= allNodeWithClients.keys
    }
  }.startWatching()

  //get all regions
  //32768->(1,2), 32769->(1), ...
  val allRegionWithNodes = mutable.Map[Long, Iterable[Int]]()
  val regionsWatcher = new RegionWatcher(zookeeper) with ChildNodePathAware[(Long, Int)] {
    override def onChanged(batch: Iterable[(Long, Int)]): Unit = {
      allRegionWithNodes.clear()
      allRegionWithNodes ++= batch.groupBy(_._1).map(x => x._1 -> x._2.map(_._2))
    }
  }.startWatching()

  val writerSelector = new RoundRobinSelector(ring, allNodeWithClients);

  private def assertNodesNotEmpty() {
    if (allNodeWithClients.isEmpty) {
      throw new RegionFsClientException("no serving data nodes")
    }
  }

  def writeFile(content: ByteBuffer): Future[FileId] = {
    assertNodesNotEmpty();

    val client = writerSelector.select()
    client.writeFile(content)
  }

  def readFile[T](fileId: FileId, rpcTimeout: Duration): InputStream = {
    val client = getSafeClient(fileId)
    client.readFile(fileId, rpcTimeout)
  }

  private def getSafeClient[T](fileId: FileId): FsNodeClient = {
    assertNodesNotEmpty();
    //TODO: may not be noticed immediately
    val nodeId: Int = allRegionWithNodes.get(fileId.regionId).map(_.head).getOrElse((fileId.regionId >> 16).toInt)
    val maybeClient = allNodeWithClients.get(nodeId);
    if (maybeClient.isEmpty)
      throw new WrongFileIdException(fileId);

    maybeClient.get
  }

  private def getMasterClient[T](fileId: FileId): FsNodeClient = {
    assertNodesNotEmpty();
    val nodeId = (fileId.regionId >> 16).toInt;
    val maybeClient = allNodeWithClients.get(nodeId);
    if (maybeClient.isEmpty)
      throw new WrongFileIdException(fileId);

    maybeClient.get
  }

  def deleteFile[T](fileId: FileId): Future[Boolean] = {
    val client = getMasterClient(fileId)
    client.deleteFile(fileId)
  }

  def close = {
    nodesWatcher.stop()
    regionsWatcher.stop()
    clientFactory.close()
    zookeeper.close()
  }
}

/**
  * FsNodeClient factory
  */
class FsNodeClientFactory(globalConfig: GlobalConfig) {
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

    new FsNodeClient(globalConfig: GlobalConfig, endPointRef, remoteAddress)
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
class FsNodeClient(globalConfig: GlobalConfig, val endPointRef: HippoEndpointRef, val remoteAddress: RpcAddress) extends Logging {
  private def safeCall[T](body: => T): T = {
    try {
      body
    }
    catch {
      case e: Throwable => throw new ServerRaisedException(e)
    }
  }

  def writeFile(content: ByteBuffer): Future[FileId] = {
    safeCall {
      val crc32 =
        if (globalConfig.enableCrc) {
          CrcUtils.computeCrc32(content.duplicate())
        }
        else {
          0
        }

      endPointRef.askWithStream[SendFileResponse](
        SendFileRequest(content.remaining(), crc32),
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

  def getPatchInputStream(regionId: Long, revision: Long, rpcTimeout: Duration): InputStream = {
    safeCall {
      endPointRef.getInputStream(
        GetRegionPatchRequest(regionId, revision),
        rpcTimeout)
    }
  }
}

trait FsNodeSelector {
  def select(): FsNodeClient;
}

class RoundRobinSelector(nodes: Ring[Int], map: mutable.Map[Int, FsNodeClient]) extends FsNodeSelector {
  def select(): FsNodeClient = {
    nodes.synchronized {
      map(nodes !)
    }
  }
}

class RegionFsException(msg: String, cause: Throwable = null)
  extends RuntimeException(msg, cause) {

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