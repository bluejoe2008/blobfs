package org.grapheco.regionfs

import java.io.{File, FileInputStream}
import java.util.Properties
import java.util.concurrent.Executors

import org.grapheco.commons.util.ConfigUtils._
import org.grapheco.commons.util.{Configuration, ConfigurationEx}
import org.grapheco.regionfs.util.ZooKeeperClient

import scala.collection.JavaConversions
import scala.concurrent.ExecutionContext

/**
  * Created by bluejoe on 2020/2/6.
  */
class GlobalSetting(props: Properties) {
  val conf = new Configuration {
    override def getRaw(name: String): Option[String] =
      if (props.containsKey(name)) {
        Some(props.getProperty(name))
      } else {
        None
      }
  }

  lazy val consistencyStrategy: Int = conf.get("consistency.strategy").
    withDefault(Constants.CONSISTENCY_STRATEGY_STRONG).
    withOptions(Map("strong" -> Constants.CONSISTENCY_STRATEGY_STRONG,
      "eventual" -> Constants.CONSISTENCY_STRATEGY_EVENTUAL)).asInt

  lazy val minWritableRegions: Int = conf.get("region.min.writable").withDefault(Constants.DEFAULT_MIN_WRITABLE_REGIONS).asInt
  lazy val replicaNum: Int = conf.get("replica.num").withDefault(1).asInt
  lazy val regionSizeLimit: Long = conf.get("region.size.limit").withDefault(Constants.DEFAULT_REGION_SIZE_LIMIT).asLong
  lazy val enableCrc: Boolean = conf.get("blob.crc.enabled").withDefault(true).asBoolean
  lazy val regionVersionCheckInterval: Long = conf.get("region.version.check.interval").withDefault(
    Constants.DEFAULT_REGION_VERSION_CHECK_INTERVAL).asLong
}

object GlobalSetting {
  def empty = new GlobalSetting(new Properties())
}

class GlobalSettingWriter {
  def write(props: Properties): Unit = {
    val conf = new ConfigurationEx(props)

    val zks = conf.get("zookeeper.address").asString
    val zk = ZooKeeperClient.create(zks)

    zk.createAbsentNodes();
    zk.saveglobalSetting(props)

    zk.close()
  }

  def write(map: Map[String, String]): Unit = {
    val props = new Properties();
    props.putAll(JavaConversions.mapAsJavaMap(map))
    write(props)
  }

  def write(configFile: File): Unit = {
    val props = new Properties();
    val fis = new FileInputStream(configFile)
    props.load(fis)
    write(props)
    fis.close()
  }
}