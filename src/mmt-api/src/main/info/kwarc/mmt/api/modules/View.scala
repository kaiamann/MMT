package info.kwarc.mmt.api.modules
import info.kwarc.mmt.api._
import objects._
import symbols._
import utils.xml.addAttrOrChild

/**
 * A View represents an MMT view.
 *
 * Views may be declared (given by a list of assignments) or defined (given by an existing morphism).
 * These cases are distinguished by which subtrait of Link is mixed in.
 *
 * @param doc the URI of the parent document
 * @param name the name of the view
 */
abstract class View(doc : DPath, name : LocalName) extends Module(doc, name) with Link {
   val feature = "view"
   def namePrefix = LocalName(path)
   
   protected def outerString = path + " : " + from.toString + " -> " + to.toString
   def toNode = {
      val node = <view name={name.last.toPath} base={doc.toPath} implicit={if (isImplicit) "true" else null}>
           {innerNodes}
         </view>
      val fromN = Obj.toStringOrNode(from)
      val toN = Obj.toStringOrNode(to)
      addAttrOrChild(addAttrOrChild(node, "to", toN), "from", fromN)
   }
}

 /**
  * A DeclaredView represents an MMT view given by a list of assignments.
  *
  * @param doc the namespace/parent document
  * @param name the name of the view
  * @param from the domain theory
  * @param to the codomain theory
  * @param isImplicit true iff the link is implicit
  */
class DeclaredView(doc : DPath, name : LocalName, val fromC : TermContainer, val toC : TermContainer, val isImplicit : Boolean)
      extends View(doc, name) with DeclaredModule with DeclaredLink {
   def getInnerContext = codomainAsContext
   def getComponents = List(DomComponent(new FinalTermContainer(from)),CodComponent(new FinalTermContainer(to)))

   def translate(newNS: DPath, newName: LocalName, translator: Translator,context:Context): DeclaredView = {
     def tl(m: Term)= translator.applyModule(context, m)
     val res = new DeclaredView(newNS, newName, fromC map tl, toC map tl, isImplicit)
     getDeclarations foreach {d =>
       res.add(d.translate(res.toTerm, LocalName.empty, translator,context))
     }
     res
   }
}

object DeclaredView {
   def apply(doc : DPath, name : LocalName, from : Term, to : Term, isImplicit: Boolean) =
      new DeclaredView(doc, name, TermContainer(from), TermContainer(to), isImplicit)
}


/**
   * A DefinedView represents an MMT view given by an existing morphism.
   *
   * @param doc the URI of the parent document
   * @param name the name of the view
   * @param from the domain theory
   * @param to the codomain theory
   * @param dfC the definiens
   * @param isImplicit true iff the link is implicit
   */
class DefinedView(doc : DPath, name : LocalName, val fromC: TermContainer, val toC: TermContainer, val dfC : TermContainer, val isImplicit : Boolean)
      extends View(doc, name) with DefinedModule with DefinedLink {
   def getComponents = List(DeclarationComponent(DefComponent, dfC))
   def translate(newNS: DPath, prefix: LocalName, translator: Translator, context : Context): DefinedModule = {
     def tl(m: Term)= translator.applyModule(context, m)
     new DefinedView(newNS, prefix/name, fromC map tl, toC map tl, dfC map tl, isImplicit)
   }
}

object DefinedView {
   def apply(doc : DPath, name : LocalName, from : Term, to : Term, df : Term, isImplicit: Boolean) =
      new DefinedView(doc, name, TermContainer(from), TermContainer(to), TermContainer(df), isImplicit)
}
