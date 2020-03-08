package org.grapheco.regionfs

/**
  * Created by bluejoe on 2020/2/7.
  */
object Constants {
  val DEFAULT_REGION_SIZE_LIMIT: Long = 1024L * 1024 * 1024 * 20
  //20G
  val WRITE_CHUNK_SIZE: Int = 1024 * 10
  val READ_CHUNK_SIZE: Int = 1024 * 1024 * 10
  val METADATA_ENTRY_LENGTH_WITH_PADDING = 40
  val REVISION_ENTRY_LENGTH_WITH_PADDING = 32
  val REGION_FILE_BODY_EOF: Array[Byte] = "\r\n----\r\n".getBytes
  val REGION_FILE_BODY_EOF_LENGTH = REGION_FILE_BODY_EOF.length
  val SERVER_SIDE_READ_BUFFER_SIZE = 4096;
  val DEFAULT_REGION_VERSION_CHECK_INTERVAL: Long = 60000 * 60;

  val MARK_GET_REGION_PATCH_SERVER_IS_BUSY: Byte = 1;
  val MARK_GET_REGION_PATCH_ALREADY_UP_TO_DATE: Byte = 2;
  val MARK_GET_REGION_PATCH_OK: Byte = 3;
  val MAX_BUSY_TRAFFIC: Int = 5;
}