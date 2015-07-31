package info.kwarc.mmt.api.archives

import info.kwarc.mmt.api._
import info.kwarc.mmt.api.modules.Module
import info.kwarc.mmt.api.ontology._
import info.kwarc.mmt.api.parser._
import info.kwarc.mmt.api.utils._
import info.kwarc.mmt.api.documents._

/**
 * a build target for computing structure dependencies
 *
 */
class Relational extends TraversingBuildTarget {
  /** source by default, may be overridden */
  def inDim = source

  def key = "mmt-deps"

  /** relational */
  val outDim = relational

  val parser = new KeywordBasedParser(DefaultObjectParser)

  override val outExt = "rel"

  override def start(_args: List[String]) {
    controller.extman.addExtension(parser)
  }

  def includeFile(s: String) = s.endsWith(".mmt")

  def buildFile(bf: BuildTask): Unit = {
    val inPathOMDoc = bf.inPath.toFile.setExtension("omdoc").filepath
    val dpath = DPath(bf.base / inPathOMDoc.segments) // bf.narrationDPath except for extension
    val ps = new ParsingStream(bf.base / bf.inPath.segments, dpath, bf.archive.namespaceMap, "mmt", File.Reader(bf.inFile))
    val doc = parser(ps)(bf.errorCont)
    writeToRel(doc, bf.archive / relational / bf.inPath)
    doc.getModulesResolved(controller.globalLookup) foreach { mod => indexModule(bf.archive, mod) }
  }

  override def buildDir(bd: BuildTask, builtChildren: List[BuildTask]): Unit = {
    bd.outFile.up.mkdirs
    val doc = controller.get(DPath(bd.archive.narrationBase / bd.inPath.segments)).asInstanceOf[Document]
    val inPathFile = Archive.narrationSegmentsAsFile(bd.inPath, "omdoc")
    writeToRel(doc, bd.archive / relational / inPathFile)
    val rs: RelStore = controller.depstore
    val docs = rs.querySet(doc.path, +Declares * HasType(IsDocument))
    docs.foreach { d =>
      val s = rs.querySet(d, +Declares * RelationExp.Deps * -Declares * HasType(IsDocument))
      println(d, (s - d).flatMap(docPathToFilePath))
    }
  }

  def docPathToFilePath(p: Path): List[File] = p match {
    case DPath(uri) =>
      controller.backend.getStores.filter(_.isInstanceOf[Archive]).
        flatMap { s =>
          val a = s.asInstanceOf[Archive]
          if (a.narrationBase <= uri) Some(a) else None
        }.map { a =>
        val b = a.narrationBase.toString
        File(a.rootString + "/source" + uri.toString.stripPrefix(b)).setExtension("mmt")
      }
    case _ => Nil
  }


  /** extract and write the relational information about a knowledge item */
  private def writeToRel(se: StructuralElement, file: File): Unit = {
    val relFile = file.setExtension("rel")
    log("[  -> relational]     " + relFile.getPath)
    val relFileHandle = File.Writer(relFile)
    controller.relman.extract(se) { r =>
      relFileHandle.write(r.toPath + "\n")
      controller.depstore += r
    }
    relFileHandle.close()
  }

  /** index a module */
  private def indexModule(a: Archive, mod: Module): Unit = {
    // write relational file
    writeToRel(mod, a / relational / Archive.MMTPathToContentPath(mod.path))
  }
}