package regionfs

import java.io.File

import org.grapheco.regionfs.FileId
import org.grapheco.regionfs.server.Region
import org.junit.{Assert, Test}

/**
  * Created by bluejoe on 2020/3/8.
  */
class FileReadWriteWith3NodesTest extends FileReadWriteTest {
  override val con = new MultiNode

  private def assertRegion(nodeId: Int, regionId: Long)(op: (Region) => Unit) = {
    val region = servers(nodeId - 1).localRegionManager.regions(regionId)
    op(region)
  }

  private def getNodeId(fid: FileId): Int = (fid.regionId >> 16).toInt

  @Test
  def testDistributed(): Unit = {
    Assert.assertEquals(3, admin.getNodes().size)
    val fid1 = super.writeFile(new File(s"./testdata/inputs/999"))
    Assert.assertEquals(1, getNodeId(fid1))
    Assert.assertEquals(3, admin.getNodes(fid1.regionId).size)

    assertRegion(1, fid1.regionId) {
      (region) =>
        Assert.assertEquals(65537, region.regionId);
        Assert.assertEquals(1, region.fileCount);
        Assert.assertEquals(1, region.revision);
        Assert.assertEquals(true, region.isPrimary);
    }

    //now, region should be sync-ed
    assertRegion(2, fid1.regionId) {
      (region) =>
        Assert.assertEquals(65537, region.regionId);
        Assert.assertEquals(1, region.fileCount);
        Assert.assertEquals(1, region.revision);
        Assert.assertEquals(false, region.isPrimary);
    }
    assertRegion(3, fid1.regionId) {
      (region) =>
        Assert.assertEquals(65537, region.regionId);
        Assert.assertEquals(1, region.fileCount);
        Assert.assertEquals(1, region.revision);
        Assert.assertEquals(false, region.isPrimary);
    }

    val fid2 = super.writeFile(new File(s"./testdata/inputs/999"))
    Assert.assertEquals(2, getNodeId(fid2))
    Assert.assertEquals(3, admin.getNodes(fid2.regionId).size)

    assertRegion(2, fid2.regionId) {
      (region) =>
        Assert.assertEquals(131073, region.regionId);
        Assert.assertEquals(1, region.fileCount);
        Assert.assertEquals(1, region.revision);
        Assert.assertEquals(true, region.isPrimary);
    }

    assertRegion(1, fid2.regionId) {
      (region) =>
        Assert.assertEquals(131073, region.regionId);
        Assert.assertEquals(1, region.fileCount);
        Assert.assertEquals(1, region.revision);
    }

    val fid3 = super.writeFile(new File(s"./testdata/inputs/999"))
    Assert.assertEquals(3, getNodeId(fid3))
    Assert.assertEquals(3, admin.getNodes(fid3.regionId).size)

    val fid4 = super.writeFile(new File(s"./testdata/inputs/999"))
    Assert.assertEquals(1, getNodeId(fid4))
    Assert.assertEquals(3, admin.getNodes(fid4.regionId).size)

    assertRegion(1, fid4.regionId) {
      (region) =>
        Assert.assertEquals(65537, region.regionId);
        Assert.assertEquals(2, region.fileCount);
        Assert.assertEquals(2, region.revision);
    }

    Thread.sleep(2000);
    assertRegion(2, fid4.regionId) {
      (region) =>
        Assert.assertEquals(65537, region.regionId);
        Assert.assertEquals(2, region.fileCount);
        Assert.assertEquals(2, region.revision);
    }
    assertRegion(3, fid4.regionId) {
      (region) =>
        Assert.assertEquals(65537, region.regionId);
        Assert.assertEquals(2, region.fileCount);
        Assert.assertEquals(2, region.revision);
    }
  }
}
