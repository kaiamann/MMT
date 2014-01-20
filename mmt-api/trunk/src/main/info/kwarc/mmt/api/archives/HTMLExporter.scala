package info.kwarc.mmt.api.archives

import info.kwarc.mmt.api._
import modules._
import symbols._
import documents._
import presentation._
import frontend._
import objects._
import utils._

class HTMLPresenter extends Presenter with NotationBasedPresenter {
   val key = "html"
   val outDim = Dim("export", "html")
   override val outExt = "html"
   private lazy val mmlPres = new presentation.MathMLPresenter(controller) // must be lazy because controller is provided in init only
     
   def apply(s : StructuralElement, standalone: Boolean = false)(implicit rh : RenderingHandler) = {
     this._rh = rh
     s match { 
       case doc : Document => 
         doHTMLOrNot(doc.path, standalone) {doDocument(doc)}
       case thy : DeclaredTheory => 
         doHTMLOrNot(thy.path.doc, standalone) {doTheory(thy)}
       case view : DeclaredView =>
         doHTMLOrNot(view.path.doc, standalone) {doView(view)}
       case _ => rh("TODO: Not implemented yet, presentation function for " + s.getClass().toString())
     }
     //TODO? reset this._rh 
   }
   
   def isApplicable(format : String) = format == "html"
   
   // easy-to-use HTML markup
   private val htmlRh = new utils.HTML(s => rh(s))
   import htmlRh._
   
   private def doName(s: String) {
      span("name") {rh(s)}
   }
   private def doMath(t: Obj) {
        rh(mmlPres.asString(t))
   }
   private def doComponent(comp: DeclarationComponent, t: Obj) {
      td {span {rh(comp.toString)}}
      td {doMath(t)}
   }
   private def doNotComponent(comp: NotationComponent, tn: parser.TextNotation) {
      td {span {rh(comp.toString)}}
      td {span {rh(tn.toText)}}
   }
   private val scriptbase = "https://svn.kwarc.info/repos/MMT/src/mmt-api/trunk/resources/mmt-web/script/"
   private val cssbase    = "https://svn.kwarc.info/repos/MMT/src/mmt-api/trunk/resources/mmt-web/css/"
   /*
    * @param dpath identifies the directory (needed for relative paths)
    */
   private def doHTMLOrNot(dpath: DPath, doit: Boolean)(b: => Unit) {
      if (! doit) return b
      val pref = Range(0,dpath.uri.path.length+2).map(_ => "../").mkString("")
      html {
        head {
          css(cssbase+"mmt.css")
          css(cssbase+"JOBAD.css")
          css(cssbase+"jquery/jquery-ui.css")
          css(pref + "html.css")
          javascript(scriptbase + "jquery/jquery.js")
          javascript(scriptbase + "jquery/jquery-ui.js")
          javascript(scriptbase + "jobad/deps/underscore-min.js")
          javascript(scriptbase + "jobad/JOBAD.js")
          javascript(scriptbase + "jobad/mmt-html.js")
          javascript(scriptbase + "jobad/modules/navigation.js")
          javascript(scriptbase + "jobad/modules/hovering.js")
          javascript(scriptbase + "jobad/modules/interactive-viewing.js")
          javascript(pref + "html.js")
        }
        body {
           b
        }
      }
   }
   def doTheory(t: DeclaredTheory) {
      div("theory") {
         div("theory-header") {doName(t.name.toString)}
         t.getPrimitiveDeclarations.foreach {
            d => div("constant") {table("constant") {
               tr("constant-header") {
                    td {doName(d.name.toString)}
                    td {
                       def toggle(label: String) {
                          span("compToggle", onclick = s"toggle(this,'$label')") {rh(label)}
                       }
                       d.getComponents.foreach {case (comp, tc) => if (tc.isDefined) 
                          toggle(comp.toString)
                       }
                       if (! d.metadata.getTags.isEmpty)
                          toggle("tags")
                       if (! d.metadata.getAll.isEmpty)
                          toggle("metadata")
                    }
               }
               d.getComponents.foreach {
                  case (comp, tc: AbstractTermContainer) =>
                     tr(comp.toString) {
                        tc.get.foreach {t =>
                            doComponent(comp, t)
                        }
                     }
                  case (comp: NotationComponent, nc: NotationContainer) =>
                     tr(comp.toString) {
                        nc(comp).foreach {n =>
                           doNotComponent(comp, n)
                         }
                     }
               }
               if (! d.metadata.getTags.isEmpty) tr("tags") {
                  td {rh("tags")}
                  td {d.metadata.getTags.foreach {
                     k => div("tag") {rh(k.toPath)}
                  }}
               }
               def doKey(k: GlobalName) {
                  td{span("key", title=k.toPath) {rh(k.toString)}}
               }
               d.metadata.getAll.foreach {
                  case metadata.Link(k,u) => tr("link metadata") {
                     doKey(k)
                     td {a(u.toString) {rh(u.toString)}}
                  }
                  case md: metadata.MetaDatum => tr("metadatum metadata") {
                     doKey(md.key)
                     td {doMath(md.value)}
                  }
               }
            }
         }}
      }
   }
   def doView(v: DeclaredView) {}
   override def exportNamespace(dpath: DPath, bd: BuildDir, namespaces: List[(BuildDir,DPath)], modules: List[(BuildFile,MPath)]) {
      doHTMLOrNot(dpath, true) {div("namespace") {
         namespaces.foreach {case (bd, dp) =>
            div("subnamespace") {
               val name = bd.dirName + "/" + bd.outFile.segments.last
               a(name) {
                  rh(dp.toPath)
               }
            }
         }
         modules.foreach {case (bf, mp) =>
            div("submodule") {
               a(bf.outFile.segments.last) {
                  rh(mp.toPath)
               }
            }
         }
      }}
   }
   def doDocument(doc: Document) {
      html {
         body {
            ul {doc.getItems foreach {
               case d: DRef =>
                  li("dref") {
                     a(d.target.toPath) {
                        rh(d.target.last)
                     }
                  }
               case m: MRef =>
                  li("mref") {
                     a(m.target.toPath) {
                        rh(m.target.last)
                     }
                  }
            }}
         }
      }
   }
}

