package info.kwarc.mmt.uom
import info.kwarc.mmt.api._
import info.kwarc.mmt.api.frontend._
import info.kwarc.mmt.api.modules._
import info.kwarc.mmt.api.symbols._
import info.kwarc.mmt.api.utils.MyList._
import info.kwarc.mmt.api.objects._
import java.io._
import scala.Console._
import scala.tools.nsc.util._

object Synthesizer extends {
   val report = new frontend.FileReport(new java.io.File("uom.log"))
   val checker = new libraries.FoundChecker(libraries.DefaultFoundation)
   } with Controller(checker, report) {

   val uomstart = "  // UOM start "
   val uomend = "  // UOM end"

   private def getCode(in : BufferedReader) : String = {
     var line = in.readLine
     if (line == null)
       throw new Exception("Synthesizer: No closing //UOM end tag found") 
     if (line.startsWith(uomend)) {
       return ""
     }
     return line + getCode(in)
   }

   def getSnippets(in : BufferedReader) : List[(GlobalName, String)] = {
     var line : String = null

     line = in.readLine
     if (line == null) // finished reading file
       return Nil
     if (line.startsWith(uomstart)) {
       line = line.substring(uomstart.length()) // remove the UOM start tag
       return (Path.parseS(line, base), getCode(in))::getSnippets(in)
     }
     return getSnippets(in)
   }

   def main(args: Array[String]) {
     /* location of the scala file  */
	   val scalafile = new BufferedReader(new FileReader(args(0))) 
	   val snippets = getSnippets(scalafile)
     scalafile.close

	   handle(Local)
     /* physical location of the document */
	   val docPath = DPath(utils.xml.URI(new java.io.File(args(1)).toURI))
     handle(Read(docPath))


	   snippets foreach {
	  	 case (path,code) =>
	  	   val oldcons : Constant = library.get(path) match {
           case cons : Constant => cons
           case _ => throw new Exception(
             "Synthesizer: Path does not point to a constant"
           )
         }
	  	   val newcons = new Constant(oldcons.home, oldcons.name, oldcons.tp, 
           Some(OMFOREIGN(scala.xml.Text(code))), null) 
	  	   library.update(newcons)
	   }
     val dpath = Path.parseD(args(2), base)


	   val doc = try {
       docstore.get(dpath)
     } 
     catch {
       case NotFound(p) => println(p.toPath + " not found"); 
       exit
     }

//     handle(GetAction(ToFile(ToString(Get(dpath)),new java.io.File(args(1)))))
//     handle(GetAction(ToFile(Deps(dpath),new java.io.File(args(1)))))
   }
}

