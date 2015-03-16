package info.kwarc.mmt.api
import utils._
import parser.SourceRef

import scala.xml.PrettyPrinter

/** The superclass of all Errors generated by MMT
 * @param shortMsg the error message
 */
abstract class Error(val shortMsg : String) extends java.lang.Exception(shortMsg) {
   /** additional message text, override as needed */
   def extraMessage = ""
   /** the severity of the error, override as needed */ 
   def level = Level.Error
   private var causedBy: Option[Throwable] = None
   /** get the error due to which this error was thrown */ 
   def setCausedBy(e: Throwable): this.type = {
      causedBy = Some(e)
      this
   }
   protected def causedByToNode = causedBy match {
      case Some(e: Error) => e.toNode
      case Some(e) => <cause type={e.getClass.getName} shortMsg={e.getMessage}>{Stacktrace.asNode(e)}</cause>
      case None => Nil
   }
   protected def causedByToString = causedBy match {
     case None => ""
     case Some(e: Error) => "\n\ncaused by\n" + e.toStringLong
     case Some(e) => "\n\ncaused by\n" + e.getClass + ": " + e.getMessage + e.getStackTrace.map(_.toString).mkString("\n","\n","")
   }
   override def toString = shortMsg
   def toStringLong : String = {
      shortMsg + "\n" + extraMessage + "\ndetected at\n" + Stacktrace.asString(this) + causedByToString
   }
   def toNode : scala.xml.Elem =
      <error type={this.getClass.getName} shortMsg={this.shortMsg} level={this.level.toString}>{if (extraMessage.isEmpty) Nil
        else extraMessage}{Stacktrace.asNode(this)}{causedByToNode}</error>
}

/**
 * auxiliary functions for handling Java stack traces
 */
object Stacktrace {
   def asString(e: Throwable) = e.getStackTrace.map(_.toString).mkString("","\n","")
   def asNode(e: Throwable) = e.getStackTrace.toList match {
     case Nil => Nil
     case st => <stacktrace>{st.map(e => <element>{e.toString}</element>)}</stacktrace>
   }
}

/** error levels, see [[Error]] */
object Level {
  type Level = Int
  val Info = 0
  val Warning = 1
  val Error = 2
  val Fatal = 3

  def parse(s: String) = s match {
    case "0" => Info
    case "1" => Warning
    case "" | "2" => Error
    case "3" => Fatal
    case _ => throw ParseError("unknown error level: " + s)
  }
}

import Level._

/** other errors that occur during parsing */
case class ParseError(s : String) extends Error("parse error: " + s)
/** errors that occur when parsing a knowledge item */
case class SourceError(
    origin: String, ref: SourceRef, mainMessage: String, extraMessages: List[String] = Nil, override val level: Level = Level.Error
 ) extends Error(mainMessage) {
    override def extraMessage = s"source error ($origin) at " + ref.toString + extraMessages.mkString("\n","\n","\n")
    override def toNode = xml.addAttr(xml.addAttr(super.toNode, "sref", ref.toString), "target", origin)
}
/** errors that occur during compiling */
object CompilerError {
  def apply(key: String, ref: SourceRef, messageList : List[String], level: Level) =
      SourceError(key, ref, messageList.head, messageList.tail, level)
}

/** errors that occur when checking a knowledge item (generated by the Checker classes) */
abstract class Invalid(s: String) extends Error(s)
/** errors that occur when structural elements are invalid */
case class InvalidElement(elem: StructuralElement, s : String, causedBy: Error = null) extends Invalid("invalid element: " + s + ": " + elem.path.toPath) {
    if (causedBy != null) setCausedBy(causedBy)
}    
/** errors that occur when objects are invalid */
case class InvalidObject(obj: objects.Obj, s: String) extends Invalid("invalid object (" + s + "): " + obj)
/** errors that occur when judgements do not hold */
case class InvalidUnit(unit: checking.CheckingUnit, history: checking.History, msg: String) extends Invalid("invalid unit: " + msg)

/** other errors */
case class GeneralError(s : String) extends Error("general error: " + s)

/** errors that occur when adding a knowledge item */
case class AddError(s : String) extends Error("add error: " + s)
/** errors that occur when updating a knowledge item */
case class UpdateError(s : String) extends Error("update error: " + s)
/** errors that occur when deleting a knowledge item */
case class DeleteError(s : String) extends Error("delete error: " + s)
/** errors that occur when retrieving a knowledge item */
case class GetError(s : String) extends Error("get error: " + s)
/** errors that occur when the backend believes it should find an applicable resource but cannot */
case class BackendError(s: String, p : Path) extends Error("Cannot find resource " + p.toString + ": " + s)
/** errors that occur when presenting a knowledge item */
case class PresentationError(s : String) extends Error(s)
/** errors that occur when registering extensions  */
case class RegistrationError(s : String) extends Error(s)
/** errors that are not supposed to occur, e.g., when input violates the precondition of a method */
case class ImplementationError(s : String) extends Error("implementation error: " + s)      
/** errors that occur during substitution with name of the variable for which the substitution is defined */
case class SubstitutionUndefined(name: LocalName, m: String) extends Error("Substitution undefined at " + name.toString + "; " + m)

case class LookupError(name : LocalName, context: objects.Context) extends Error("variable " + name.toString + " not declared in context " + context)

/** base class for errors that are thrown by an extension */
abstract class ExtensionError(prefix: String, s : String) extends Error(prefix + ": " + s)

/**
 * the type of continuation functions for error handling
 * 
 * An ErrorHandler is passed in most situations in which a component (in particular [[archives.BuildTarget]]s)
 * might produce a non-fatal error.
 */
abstract class ErrorHandler {
   private var newErrors = false
   def mark {newErrors = false}
   /** true if new errors occurred since the last call to mark */
   def hasNewErrors = newErrors
   /**
    * registers an error
    * 
    * This should be called exactly once on every error, usually in the order in which they are found.
    */
   def apply(e: Error) {
      newErrors = true
      addError(e)
   }
   protected def addError(e: Error)
}

/** stores errors in a list */
class ErrorContainer(report: Option[frontend.Report]) extends ErrorHandler {
   private var errors: List[Error] = Nil
   protected def addError(e: Error) {
      this.synchronized {
        errors ::= e
      }
      report.foreach {r => r(e)}
   }
   def isEmpty = errors.isEmpty
   def reset {errors = Nil}
   def getErrors = errors.reverse 
}

/**
 * writes errors to a file in XML syntax
 * 
 * @param fileName the file to write the errors into (convention: file ending 'err')
 *  (only created if there are errors) 
 * @param report if given, errors are also reported
 *  
 */
class ErrorWriter(fileName: File, report: Option[frontend.Report]) extends ErrorHandler {
  private val file = File.Writer(fileName)
  file.write("<errors>\n")
  protected def addError(e: Error) {
    report.foreach {r => r(e)}
    file.write(new PrettyPrinter(240, 2).format(e.toNode) + "\n")
  }
  /**
   * closes the file
   */
  def close {
     file.write("</errors>\n")
     file.close
  }
}

/**
 * reports errors
 */
class ErrorLogger(report: frontend.Report) extends ErrorHandler {
   protected def addError(e: Error) {
      report(e)
   }
}

/**
 * throws errors
 */
object ErrorThrower extends ErrorHandler {
   protected def addError(e: Error) {
      throw e
   }
}
