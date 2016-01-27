package info.kwarc.mmt.odk.GAP

import info.kwarc.mmt.api.archives.{RedirectableDimension, BuildResult, BuildTask, Importer}
import info.kwarc.mmt.api.documents.Document
import info.kwarc.mmt.api.{ParseError, utils}
import info.kwarc.mmt.api.utils._

object JSONImporter extends Importer {
  val key = "gap-omdoc"
  def inExts = List("json")
  override def inDim = RedirectableDimension("gap")
  val reader = new GAPReader

  def importDocument(bf: BuildTask, index: Document => Unit): BuildResult = {
    //     if (bf.inFile.filepath.toString < startAt) return
    val d = bf.inFile.name
    val e = try {
      reader(JSON.parse(File.read(bf.inFile).replace("\\\n","")))//parseXML(bf.inFile)
    } catch {
      case utils.ExtractError(msg) =>
        println(msg)
        sys.exit
    }

    /*
    val conv = new PVSImportTask(controller, bf, index)
    e match {
      case d: pvs_file =>
        conv.doDocument(d)
      case m: syntax.Module =>
        conv.doDocument(pvs_file(List(m)))
      //conv.doModule(m)
    }
    */
    val conv = new Translator(controller, bf, index)
    conv(reader)
    BuildResult.empty
  }
}

abstract class GAPObject {
  val name : String
  val implied : List[String]
  private var impls : Set[GAPObject] = Set()
  private var computed = false

  def implications(all:List[GAPObject]) : Set[GAPObject] = if (!computed) {
    impls = implied.map(ref =>
      if (ref == "<<unknown>>") this else
      all.find(_.name==ref).getOrElse {
      throw new ParseError("GAP Object not found in import: " + ref)
    }).toSet - this
    computed = true
    impls
  } else impls

  override def toString = this.getClass.getSimpleName + " " + name + impls.map(s =>
    "\n - " + s.getClass.getSimpleName + " " + s.name.toString).mkString("")
}

case class GAPProperty(name : String, implied : List[String]) extends GAPObject
case class GAPCategory(name : String, implied : List[String]) extends GAPObject
case class GAPAttribute(name : String, implied : List[String]) extends GAPObject
case class GAPTester( name : String, implied : List[String]) extends GAPObject
case class GAPRepresentation(name : String, implied : List[String]) extends GAPObject
case class GAPFilter(name : String, implied : List[String]) extends GAPObject

class GAPReader {

  var properties : List[GAPProperty] = Nil
  var categories : List[GAPCategory] = Nil
  var attributes : List[GAPAttribute] = Nil
  var tester : List[GAPTester] = Nil
  var representations : List[GAPRepresentation] = Nil
  var filter : List[GAPFilter] = Nil

  def all = properties ++ categories ++ attributes ++ tester ++ representations ++ filter

  private def convert(name : JSONString,j : JSON) {
    j match {
      case obj : JSONObject =>
        val tp = obj("type") match {
          case Some(s : JSONString) => s.value
          case _ => throw new ParseError("Type missing or not a JSONString: " + obj)
        }
        val impls = obj("implied") match {
          case Some(l : JSONArray) => l.values.toList match {
            case ls : List[JSONString] => ls.map(_.value)
            case _ => throw new ParseError("implied not a List of JSONStrings: " + obj + "\n" + l.values.getClass)
          }
          case _ => throw new ParseError("implied missing or not a JSONArray: " + obj)
        }
        if (tp=="GAP_Property")             properties      ::= GAPProperty(name.value,impls)
        else if (tp=="GAP_Category")        categories      ::= GAPCategory(name.value,impls)
        else if (tp=="GAP_Attribute")       attributes      ::= GAPAttribute(name.value,impls)
        else if (tp=="GAP_Tester")          tester          ::= GAPTester(name.value,impls)
        else if (tp=="GAP_Representation")  representations ::= GAPRepresentation(name.value,impls)
        else if (tp=="GAP_Filter")          filter          ::= GAPFilter(name.value,impls)
        else throw new ParseError("Type not yet implemented: " + tp)
      case _ => throw new ParseError("Not a JSON Object: " + j)
    }
  }

  def apply(json: JSON) {
    json match {
      case obj : JSONObject => obj.map.foreach(p => convert(p._1,p._2))
      case _ => throw new ParseError("Not a JSON Object")
    }
  }

}