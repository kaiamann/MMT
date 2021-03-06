package info.kwarc.mmt.lf.induction

import info.kwarc.mmt.api._
import objects._
import symbols._
import notations._
import checking._
import modules._

import info.kwarc.mmt.lf._
//import scala.collection.parallel.ParIterableLike.Copy

private object InductiveTypes {
  /**
    * Make a new unique local name from the given string
    * @param nm the string from which the local name is generated
    */
  def uniqueLN(nm: String): LocalName = {
    LocalName("") / nm
  }
   
  /**
    * Make a new variable declaration for a variable of type tp and fresh name (preferably name) in the given (optional) context
    * @param name the local name that will preferably be picked for the variable declaration
    * @param tp the type of the variable
    * @param the context in which to pick the a fresh variable (optional)
    * @note postcondition: the returned variable is free in the given context
    */
   def newVar(name: LocalName, tp: Term, con: Option[Context] = None) : VarDecl = {
      val c = con.getOrElse(Context.empty)
      val (freshName,_) = Context.pickFresh(c, name)
      VarDecl(freshName, tp)
   }

  val theory: MPath = LF._base ? "Inductive"
  object Eq extends BinaryLFConstantScala(theory, "eq")
  object Neq extends BinaryLFConstantScala(theory, "neq")
  
  val Contra = OMS(theory ? "contra") 
}

import InductiveTypes._

/** helper class for the various declarations in an inductive type */ 
sealed abstract class InductiveDecl {
  def path: GlobalName
  def name = path.name
  def args: List[(Option[LocalName], Term)]
  def ret: Term
  def toTerm = FunType(args,ret)
  /**
    * Built a context quantifying over all arguments
    *  and the variable declaration for the result of this constructor applied to those arguments
    * @param suffix (optional) a suffix to append to the local name of each variable declaration in the returned context
    */
  def argContext(suffix: Option[String]): (Context, VarDecl) = { 
      val dargs= args.zipWithIndex map {
      case ((Some(loc), arg), _) => (loc, arg)
      case ((None, arg), i) => (uniqueLN(i.toString), arg)
    }
    val con = dargs map {case (loc, tp) =>
      val n = suffix match {
        case Some(s) => loc / s
        case None => loc
      }
      newVar(n, tp, None)
    }
    val tm = ApplySpine(OMS(path), con.map(_.toTerm) :_*)
    (con, VarDecl(uniqueLN("return_type_of_"+name), None, Some(tm), None, None))
  }
  def toVarDecl : VarDecl = VarDecl(this.name, this.toTerm, OMS(this.path))
}

/** type declaration */
case class TypeLevel(path: GlobalName, args: List[(Option[LocalName], Term)]) extends InductiveDecl {
  def ret = Univ(1)
}

/** constructor declaration */
case class TermLevel(path: GlobalName, args: List[(Option[LocalName], Term)], ret: Term) extends InductiveDecl {
   /**
    * Substitute the argument types of a the termlevel declaration according to the substitution sub
    * @param sub the substitution to apply
    */
  def substitute(sub : Substitution) : TermLevel = {
    val subArgs = args map {
      case (Some(loc), tp) => (Some(uniqueLN(loc + "substituted_"+sub.toString())), tp ^ sub)
      case (None, tp) => (None, tp ^ sub)
    }
    TermLevel(path./("substituted_"+sub.toString()), subArgs, ret ^ sub)
  }
}

/* Rules and Judgments */
case class StatementLevel(path: GlobalName, args: List[(Option[LocalName], Term)]) extends InductiveDecl {
  def ret = Univ(1)
}

/** theories as a set of types of expressions */ 
class InductiveTypes extends StructuralFeature("inductive") with ParametricTheoryLike {

  /**
    * Checks the validity of the inductive type(s) to be constructed
    * @param dd the derived declaration from which the inductive type(s) are to be constructed
    */
  override def check(dd: DerivedDeclaration)(implicit env: ExtendedCheckingEnvironment) {
    //TODO: check for inhabitability
  }

  /**
    * Elaborate the derived declarations into the inductive type(s), defined in it
    *  and the variable declaration for the result of this constructor applied to those arguments
    * @param parent the parent declared module of the derived declaration to elaborate
    * @param dd the derived declaration to elaborate
    */
  def elaborate(parent: DeclaredModule, dd: DerivedDeclaration) = {
    // to hold the result
    var elabDecls : List[Declaration] = Nil
    var tmdecls : List[TermLevel]= Nil
    var tpdecls : List[TypeLevel]= Nil
    val decls = dd.getDeclarations map {
      case c: Constant =>
        val tp = c.tp getOrElse {throw LocalError("missing type")}        
          val FunType(args, ret) = tp
          if (JudgmentTypes.isJudgment(ret)(controller.globalLookup)) {
            StatementLevel(c.path, args)
          } else {
          ret match {
            case Univ(1) => {
              val tpdecl = TypeLevel(c.path, args)
              tpdecls ::= tpdecl 
              tpdecl
            }
            case Univ(x) if x != 1 => throw LocalError("unsupported universe")
            case r => {// TODO check that r is a type
              val tmdecl = TermLevel(c.path, args, r)
              tmdecls ::= tmdecl 
              tmdecl
            }
          }
        }
      case _ => throw LocalError("unsupported declaration")
    }
    // the type and term constructors of the inductive types and the no confusion axioms for the term constructors
    var noConfDecls : List[Declaration] = Nil
    decls foreach {d =>
      val tp = d.toTerm
      val c = Constant(parent.toTerm, d.name, Nil, Some(tp), None, None)
      elabDecls ::= c
      d match {
        case TermLevel(loc, args, tm) =>
          noConfDecls ++= noConf(parent, TermLevel(loc, args, tm),tmdecls)
        case _ =>
      }
    }
    // get the no junk axioms and build the result
    elabDecls = elabDecls.reverse ::: noConfDecls ::: noJunk(parent, decls, tpdecls, tmdecls)
    
    new Elaboration {
      def domain = elabDecls map {d => d.name}
      def getO(n: LocalName) = {
        elabDecls.find(_.name == n)
      }
    }
  }

  /**
    * Generate injectivity declaration for term constructor d
    * @param parent the parent declared module of the derived declaration to elaborate
    * @param d the term level for which to generate the injectivity axiom
    */
  private def injDecl(parent : DeclaredModule, d : TermLevel) : Declaration = {
    val (aCtx, aApplied) = d.argContext(Some("1"))
    val (bCtx, bApplied) = d.argContext(Some("2"))
    
    val argEq = (aCtx zip bCtx) map {case (a,b) => Eq(a.toTerm,b.toTerm)}
    val resEq = Eq(aApplied.toTerm, bApplied.toTerm)
    val body = Arrow(Arrow(argEq, Contra), Arrow(resEq, Contra))
    val inj = Pi(aCtx ++ bCtx,  body)
    
    Constant(parent.toTerm, uniqueLN("injective_"+d.name.toString), Nil, Some(inj), None, None)
  }
  
  /**
  * Generate no confusion/injectivity declaration for term constructor d and all term constructors of the same return type
  * @param parent the parent declared module of the derived declaration to elaborate
  * @param d the term level declaration for which to generate the no confusion declarations
  * @param tmdecls all term level declarations
  */
  private def noConf(parent : DeclaredModule, d : TermLevel, tmdecls: List[TermLevel]) : List[Declaration] = {
  var decls:List[Declaration] = Nil
  tmdecls.filter(_.ret == d.ret) foreach {e => 
    if (e == d) {
      if(d.args.length > 0)
        decls ::= injDecl(parent, d)  
    } else {
        val name = uniqueLN("no_conf_axiom_for_" + d.name.toString + "_" + e.toString)
        val (dCtx, dApplied) = d.argContext(Some("1"))
        val (eCtx, eApplied) = e.argContext(Some("2"))
        val tp = Pi(dCtx ++ eCtx, Neq(dApplied.toTerm, eApplied.toTerm))
        decls ::= Constant(parent.toTerm, name, Nil, Some(tp), None, None)
    }
  }
  decls
  }
  
  /** Generate no junk declaration for all declarations
  * @param parent the parent declared module of the derived declaration to elaborate
  * @param decls all declarations
  * @param tmdecls all term level declarations
  * @param tpdecls all type level declarations
  */    
  private def noJunk(parent : DeclaredModule, decls : List[InductiveDecl], tpdecls: List[TypeLevel], tmdecls: List[TermLevel]) : List[Declaration] = {
    var derived_decls:List[Declaration] = Nil
    //A list of quadruples consisting of (context of arguments, a, the sub replacing x with a free type a, x) for each type declaration declaring a type x
    val quant : List[(Context, VarDecl, Sub, VarDecl)]= tpdecls map {case dec => 
      val (ctx, x) = dec.argContext(None)
      val a = newVar(LocalName(""), Univ(1), None)
      (ctx, a, Sub(x.name, a.toTerm), x)
    }
    
    val subst : Substitution = Substitution.list2substitution(quant.map(_._3))
    val chain : Context = Context.list2context(decls map {case x : InductiveDecl => x.toVarDecl}) ^ subst
    //The morphisms for each defined type x
    val morphisms : List[(VarDecl, Term, Sub)]= quant map {case x:(Context, VarDecl, Sub, VarDecl) => (x._4, Pi(x._1.++(chain).++(x._2), x._4.toTerm), x._3)}
    //The corresponding declarations
    derived_decls = morphisms map {case (x, t, _) => Constant(parent.toTerm, uniqueLN("no_junk_axiom_for_type_"+x.toString), Nil, Some(t), None, None)}
    val morphs : List[(VarDecl, Sub, Declaration)] = (morphisms zip derived_decls) map {case ((vd, t, s), decl) => (vd, s, decl)}
    
    morphs foreach {case (x : VarDecl, s : Sub, t : Declaration)=>
    //Now we need to produce the noJunk axioms for the constructors of the type x
      tmdecls.filter(_.ret == x.tp.get) foreach {
        case tmdeclOrig : TermLevel => 
          //substitute the other types by their models, (in case of mutual induction)
          val tmdecl = tmdeclOrig.substitute(subst.subs.filterNot( _ == s).toList)
          val decl = tmdecl.toTerm
          val (args, a) = tmdecl.argContext(None)
          
          //the morphism x=>a
          def m(arg:Term) : Term = ApplyGeneral(t.toTerm, args.map(_.toTerm) :+ arg)
  
          //the result obtained when mapping the result of the constructor
          val orig = ApplyGeneral(decl, args.variables.map(_.toTerm).toList)
          val mappedOrig = m(orig)
          
          //the result obtained when mapping the arguments to the constructor
          val argsMapped : List[Term]= args.variables.toList map {arg =>
            if (arg.tp.get == x) {
              m(arg.toTerm)
            } else
              arg.toTerm
          }
          val mappedArgs : Term = ApplyGeneral(decl, argsMapped)

          //both results should be equal, if m is actually a homomorphism
          val assertion = Pi(args, Eq(mappedOrig, mappedArgs))
          derived_decls ::= Constant(parent.toTerm, uniqueLN("no_junk_axiom_for_constructor_"+assertion.toString), Nil, Some(assertion), None, None)
        }
      }
    derived_decls
  } 
}