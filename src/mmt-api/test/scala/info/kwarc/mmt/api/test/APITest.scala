package info.kwarc.mmt.api.test

import info.kwarc.mmt.api._
import info.kwarc.mmt.api.test.utils._
import info.kwarc.mmt.api.test.utils.testers.TestArchive
import info.kwarc.mmt.api.utils.URI


class APITest extends MMTIntegrationTest(
  TestArchive("MMT/urtheories", hasDevel = true),
//  ,"MMT/LATIN"
//  ,"MMT/LFX"
//  ,"MitM/smglom"
//  ,"MitM/interfaces"
//  ,"Mizar/MML"
//  ,"HOLLight/basic"
//  ,"PVS/Prelude"
//  ,"PVS/NASA"
)() {
  bootstrapTests()

  handleLine("log+ lmh")
  // extensions should load
  shouldLoadExtensions()
  shouldInstallArchives()

  it should "get a Constant" in {
    lazy val brackets = (DPath(URI.http colon "cds.omdoc.org") / "mmt") ? "mmt" ? "brackets"
    controller.getConstant(brackets)
  }

  /*
  shouldHandleLine("lmh clone alignments/Public")

  it should "check all alignments" in {
    handleLine("log+ archive")
    handleLine("extension info.kwarc.mmt.api.ontology.AddAlignments " + (contentFolder / "alignments" / "Public").toString)
  }
  */
}
