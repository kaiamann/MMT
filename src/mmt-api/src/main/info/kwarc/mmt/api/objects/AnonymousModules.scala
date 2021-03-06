package info.kwarc.mmt.api.objects

import info.kwarc.mmt.api._
import notations._
import utils._

/** auxiliary class for storing lists of declarations statefully without giving it a global name
 *
 * anonymous modules are object that can be converted into these helper classes using the objects [[AnonymousTheory]] and [[AnonymousMorphism]]
 */
trait AnonymousBody extends MutableElementContainer[OML] with DefaultLookup[OML] with DefaultMutability[OML] {
  var decls: List[OML]
  def getDeclarations = decls
  def setDeclarations(ds: List[OML]) {
    decls = ds
  }
  def toTerm: Term
}

/** a theory given by meta-theory and body */
class AnonymousTheory(val mt: Option[MPath], var decls: List[OML]) extends AnonymousBody {
  def rename(oldNew: (LocalName,LocalName)*) = {
    val sub: Substitution = oldNew.toList map {case (old,nw) => Sub(old, OML(nw))}
    val trav = new symbols.OMLReplacer(sub)
    val newDecls = decls map {oml =>
       // TODO this renaming is too aggressive if there is OML shadowing or if OMLs are used for other purposes
       val omlR = trav(oml, Context.empty).asInstanceOf[OML] // this traverser always maps OMLs to OMLs
       listmap(oldNew, oml.name) match {
         case Some(nw) => omlR.copy(name = nw)
         case None => omlR
       }
    }
    decls = newDecls
  }
  def toTerm = AnonymousTheory(mt, decls)
}
object AnonymousTheory {
  val path = ModExp.anonymoustheory

  def apply(mt: Option[MPath], decls: List[OML]) = OMA(OMS(path), mt.map(OMMOD(_)).toList:::decls)
  def unapply(t: Term): Option[(Option[MPath],List[OML])] = t match {
    case OMA(OMS(this.path), OMMOD(mt)::OMLList(omls)) =>
      Some((Some(mt), omls))
    case OMA(OMS(this.path), OMLList(omls)) =>
      Some((None, omls))
    case _ => None
  }

  def fromTerm(t: Term) = unapply(t).map {case (m,ds) => new AnonymousTheory(m, ds)}
}

/** a morphism given by domain, codomain, and body */
class AnonymousMorphism(val from: Term, to: Term, var decls: List[OML]) extends AnonymousBody {
  def toTerm = AnonymousMorphism(from, to, decls)
}
object AnonymousMorphism {
  val path = ModExp.anonymousmorphism

  def apply(f: Term, t: Term, decls: List[OML]) = OMA(OMS(path), f :: t :: decls)
  def unapply(t: Term): Option[(Term,Term,List[OML])] = t match {
    case OMA(OMS(this.path), f::t::OMLList(omls)) =>
      Some((f,t, omls))
    case _ => None
  }
  def fromTerm(t: Term) = unapply(t).map {case (f,t,ds) => new AnonymousMorphism(f, t, ds)}
}

object OMLList {
  // awkward casting here, but this way the list is not copied; thus, converting back and forth between Term and AnonymousTheory is cheap
  def unapply(ts: List[Term]): Option[List[OML]] = {
    if (ts.forall(_.isInstanceOf[OML]))
      Some(ts.asInstanceOf[List[OML]])
    else
      None
  }
}

class DerivedOMLFeature(val feature: String) {
   val path = Path.parseS("http://cds.omdoc.org/mmt?mmt?StructuralFeature", NamespaceMap.empty)
   def maketerm(feat : String, tp : Term) =
      OMA(OMS(path), List(OML(LocalName(feat)),tp))

   def apply(name: LocalName, tp: Term, df: Option[Term] = None, nt: Option[TextNotation] = None) =
      OML(name, Some(tp), df, nt, Some(feature))
}

object DerivedOMLFeature {
   def apply(name: LocalName, feat: String, tp: Term, df: Option[Term] = None) = OML(name, Some(tp), df, None, Some(feat))
   def unapply(o:OML): Option[(LocalName, String, Term, Option[Term])] = o match {
      case OML(n, Some(tp), df,_, Some(f)) => Some((n,f,tp,df))
      case _ => None
   }

  /** for mixing into subclasses of the companion class */
  trait Named {self: DerivedOMLFeature =>
     def unapply(o : OML): Option[(LocalName, Term, Option[Term])] = {
        if (o.featureOpt contains feature) {
           o match {
              case OML(n, Some(tp), df, None, _) => Some((n,tp,df))
              case _ => throw ImplementationError("unsupported properties of derived declaration")
           }
        } else
           None
     }
  }
  /** for mixing into subclasses of the companion class */
  trait Unnamed {self: DerivedOMLFeature =>
    def apply(p: MPath, df: Option[Term]): OML = apply(LocalName(p), OMMOD(p), df)
    def unapply(o : OML): Option[(MPath, Option[Term])] = {
        if (o.featureOpt contains feature) {
           o match {
              case OML(LocalName(ComplexStep(p)::Nil), Some(OMMOD(q)), df, _, _) if p == q => Some((p, df))
              case _ => throw ImplementationError("unsupported properties of derived declaration")
           }
        } else
           None
     }
  }
}

object IncludeOML extends DerivedOMLFeature("include") with DerivedOMLFeature.Unnamed {
   def apply(p: MPath, args: List[Term]): OML = apply(LocalName(p), OMPMOD(p, args))
}

object StructureOML extends DerivedOMLFeature("structure") with DerivedOMLFeature.Named

/**
 * a realization declaration is like a structure/include except it *postulates* the existence of a theory morphism
 * if partial, the morphism maps declarations to declarations of the same local name in the containing theory
 */
object RealizeOML extends DerivedOMLFeature("realize") with DerivedOMLFeature.Unnamed
