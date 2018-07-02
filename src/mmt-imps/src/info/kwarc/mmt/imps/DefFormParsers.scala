package info.kwarc.mmt.imps

import info.kwarc.mmt.imps.ParserWithSourcePosition._
import info.kwarc.mmt.imps.Usage.Usage

class DefFormParsers
{
  // ######### Argument Parsers

  lazy val parseName  : Parser[String] = regex("""[^()\t\r\n ]+""".r)
  lazy val parseTName : Parser[Name]   = fullParser(parseName ^^ { case nm => Name(nm,None,None)})

  // ToDo: nested strings could be a problem. Do those occur?
  lazy val parseDefString : Parser[DefString] = fullParser(regex("""\"[^\"]+\"""".r) ^^ {case s => DefString(s,None,None)})

  lazy val parseArgTheory : Parser[ArgTheory] =
    fullParser(("(theory" ~> parseTName <~ ")") ^^ { case n => ArgTheory(n,None,None) })

  lazy val parseArgTranslation : Parser[ArgTranslation] =
    fullParser(("(translation" ~> parseTName <~ ")") ^^ { case n => ArgTranslation(n,None,None) })

  lazy val parseArgLanguage : Parser[ArgLanguage] =
    fullParser(("(language" ~> parseTName <~ ")") ^^ { case n => ArgLanguage(n,None,None) })

  lazy val parseArgWitness : Parser[ArgWitness] =
    fullParser(("(witness" ~> parseDefString <~ ")") ^^ {case s => ArgWitness(s,None,None)})

  lazy val parseUsage : Parser[Usage] = parseName ^^ {
    case "elementary-macete"        => Usage.ELEMENTARYMACETE
    case "transportable-macete"     => Usage.TRANSPORTABLEMACETE
    case "rewrite"                  => Usage.REWRITE
    case "transportable-rewrite"    => Usage.TRANSPORTABLEREWRITE
    case "simplify-logically-first" => Usage.SIMPLIFYLOGICALLYFIRST
    case "d-r-convergence"          => Usage.DRCONVERGENCE
    case "d-r-value"                => Usage.DRVALUE
    case _                          => ???
  }

  lazy val parseArgUsages  : Parser[ArgUsages] =
    fullParser(("(usages" ~> rep1(parseUsage) <~ ")") ^^ { case us => ArgUsages(us,None,None) })

  lazy val parseArgSort    : Parser[ArgSort] =
    fullParser(("(sort" ~> parseTName <~ ")") ^^ {case n => ArgSort(n,None,None)})

  lazy val parseArgFixedTheories  : Parser[ArgFixedTheories] =
    fullParser(("(fixed-theories" ~> rep1(parseTName) <~ ")") ^^ { case ts => ArgFixedTheories(ts,None,None) })

  lazy val parseModTransportable  : Parser[ModTransportable] =
    fullParser("transportable" ^^ {case _ => ModTransportable(None,None)} )

  lazy val parseModNull : Parser[ModNull] = fullParser("null" ^^ {case _ => ModNull(None,None)} )

  lazy val parseSpecName : Parser[MSpecName] = fullParser(parseTName ^^ {case n => MSpecName(n,None,None)})

  lazy val parseSpecSeries : Parser[MSpecSeries] = fullParser(
    "(series" ~> rep1(parseSpec) <~ ")" ^^ { case s => MSpecSeries(s,None,None)}
  )

  lazy val parseSpecRepeat : Parser[MSpecRepeat] = fullParser(
    "(repeat" ~> rep1(parseSpec) <~ ")" ^^ { case s => MSpecRepeat(s,None,None)}
  )

  lazy val parseSpecSequential : Parser[MSpecSequential] = fullParser(
    "(sequential" ~> rep1(parseSpec) <~ ")" ^^ { case s => MSpecSequential(s,None,None)}
  )

  lazy val parseSpecSound : Parser[MSpecSound] = fullParser(
    "(sound" ~> (parseSpec ~ parseSpec ~ parseSpec) <~ ")" ^^ { case x ~ y ~ z => MSpecSound(x,y,z,None,None)}
  )

  lazy val parseSpecParallel : Parser[MSpecParallel] = fullParser(
    "(parallel" ~> rep1(parseSpec) <~ ")" ^^ { case s => MSpecParallel(s,None,None)}
  )

  lazy val parseSpecWithoutMinorPremises : Parser[MSpecWithoutMinorPremises] = fullParser(
    "(without-minor-premises" ~> parseSpec <~ ")" ^^ { case s => MSpecWithoutMinorPremises(s,None,None)}
  )

  val allMSpecs : List[Parser[MaceteSpec]] = List(
    parseSpecSeries, parseSpecRepeat, parseSpecSequential, parseSpecSound,
    parseSpecParallel, parseSpecWithoutMinorPremises, parseSpecName
  )

  lazy val parseSpec : Parser[MaceteSpec] = anyOf(allMSpecs)

  lazy val parseArgSourceTheory : Parser[ArgSourceTheory] = fullParser(
    "(source-theory" ~> parseTName <~ ")" ^^ { case n => ArgSourceTheory(n,None,None) }
  )

  lazy val parseArgSourceTheories : Parser[ArgSourceTheories] = fullParser(
    "(source-theories" ~> rep1(parseTName) <~ ")" ^^ { case ns => ArgSourceTheories(ns,None,None) }
  )

  lazy val parseInductionPrinciple : Parser[ArgInductionPrinciple] = fullParser(
    (parseDefString | parseTName) ^^ { case i => i match {
      case n@Name(_,_,_)      => ArgInductionPrinciple(Left(n),None,None)
      case d@DefString(_,_,_) => ArgInductionPrinciple(Right(d),None,None)
      case _                  => ??!(i)
    }}
  )

  lazy val parseArgBaseCaseHook : Parser[ArgBaseCaseHook] = fullParser(
    "(base-case-hook" ~> parseTName <~ ")" ^^ { case n => ArgBaseCaseHook(n,None,None) }
  )

  lazy val parseArgInductionStepHook : Parser[ArgInductionStepHook] = fullParser(
    "(induction-step-hook" ~> parseTName <~ ")" ^^ { case n => ArgInductionStepHook(n,None,None) }
  )

  lazy val parseArgDontUnfold : Parser[ArgDontUnfold] = fullParser(
    "(dont-unfold" ~> rep1(parseTName) <~ ")" ^^ { case ns => ArgDontUnfold(ns,None,None) }
  )

  // ######### Full Def-Form Parsers

  val pHeralding  : Parser[Heralding] = composeParser(
    "herald",
    List(parseTName),
    Nil,
    Nil,
    Heralding
  )

  val pAtomicSort : Parser[DFAtomicSort] = composeParser(
    "def-atomic-sort",
    List(parseTName, parseDefString),
    Nil,
    List((parseArgTheory, R), (parseArgUsages, O), (parseArgWitness, O)),
    DFAtomicSort
  )

  val pCompoundMacete : Parser[DFCompoundMacete] = composeParser(
    "def-compound-macete",
    List(parseTName, parseSpec),
    Nil,
    Nil,
    DFCompoundMacete
  )

  val pConstant : Parser[DFConstant] = composeParser(
    "def-constant",
    List(parseTName, parseDefString),
    Nil,
    List((parseArgTheory,R),(parseArgSort,O),(parseArgUsages,O)),
    DFConstant
  )

  val pImportedRewriteRules : Parser[DFImportedRewriteRules] = composeParser(
    "def-imported-rewrite-rules",
    List(parseTName),
    Nil,
    List((parseArgSourceTheory,O), (parseArgSourceTheories,O)),
    DFImportedRewriteRules
  )

  val pInductor : Parser[DFInductor] = composeParser(
    "def-inductor",
    List(parseTName, parseInductionPrinciple),
    Nil,
    List((parseArgTheory,R), (parseArgTranslation,O), (parseArgBaseCaseHook,O),
      (parseArgInductionStepHook,O), (parseArgDontUnfold,O)),
    DFInductor
  )

  val pQuasiConstructor : Parser[DFQuasiConstructor] = composeParser(
    "def-quasi-constructor",
    List(parseTName, parseDefString),
    Nil,
    List((parseArgLanguage,R),(parseArgFixedTheories,O)),
    DFQuasiConstructor
  )

  val pSchematicMacete : Parser[DFSchematicMacete] = composeParser(
    "def-schematic-macete",
    List(parseTName, parseDefString),
    List(parseModNull, parseModTransportable),
    List((parseArgTheory,R)),
    DFSchematicMacete
  )

  // ######### Complete Parsers

  val allDefFormParsers : List[Parser[DefForm]] = List(
    parseLineComment, pHeralding, pAtomicSort, pConstant, pQuasiConstructor, pSchematicMacete, pCompoundMacete,
    pInductor, pImportedRewriteRules
  )

  lazy val parseImpsSource : PackratParser[List[DefForm]] = { rep1(anyOf(allDefFormParsers)) }

}
