package info.kwarc.mmt.odk.LMFDB


import info.kwarc.mmt.api._
import backend._
import frontend._
import ontology._
import info.kwarc.mmt.api.ontology.{BaseType, Query, QueryEvaluator}
import info.kwarc.mmt.api.web.WebQuery
import info.kwarc.mmt.odk.codecs.LMFDBCoder
import modules.{DeclaredTheory, _}
import objects._
import symbols._
import utils._
import valuebases._
import info.kwarc.mmt.odk._
import info.kwarc.mmt.lf.Apply
import info.kwarc.mmt.mitm.MitM

import scala.collection.mutable.HashSet
import scala.util.Try

class Plugin extends frontend.Plugin {
  val theory = MitM.mathpath
  val dependencies = List("info.kwarc.mmt.lf.Plugin")
  override def start(args: List[String]) {
    controller.backend.addStore(LMFDBStore)
    controller.extman.addExtension(new ImplementsRuleGenerator)
    controller.extman.addExtension(LMFDBSystem)
  }
}

object LMFDB {
   val path = DPath(URI("http","www.lmfdb.org"))
   val schemaPath = path / "schema"
   val dbPath = path / "db"
   val uri = URI("http://beta.lmfdb.org") / "api"
}

object Metadata {
   val path = LMFDB.path ? "Metadata"
   val implements = path ? "implements"
   val extend = path ? "extends"
   val constructor = path ? "constructor"
   val key = path ? "key"
   val codec = path ? "codec"
}

case class DBField(jsonKey: String, codec: Codec[JSON])
case class DBTheory(tp : Term, constructor : Term, parents : List[(DB,DeclaredTheory)] = Nil) {
  def +(th : DeclaredTheory)(implicit controller: Controller) = DBTheory(tp,constructor,
    (DB.fromPath(th.path).getOrElse{throw BackendError("Not a schema theory",th.path)},th) :: parents)
}

class DB(suffix: LocalName, mod: LocalName) {
   def schemaPath: MPath = (LMFDB.schemaPath / suffix) ? mod
   def dbPath: MPath = (LMFDB.dbPath / suffix) ? mod
   def dbpath: String = suffix.toString + "/" + mod.toString
}

object DB {

  // def schema2db(sPath : MPath) : MPath = fromPath(sPath).get.dbPath
  // def db2Schema(dbPath : MPath) : MPath = fromPath(dbPath).get.schemaPath

  def fromPath(pth : Path, allowDBPath: Boolean = true, allowSchemaPath : Boolean = true) : Option[DB] = {
    // extract relevant components
    val (ndoc, mod, _) = pth.toTriple

    // if either of the components is empty, return
    if(ndoc.isEmpty || mod.isEmpty)
      return None

    // try to remove both of the prefixes
    val suffixS = if(allowSchemaPath) ndoc.get.dropPrefix(LMFDB.schemaPath) else None
    val suffixD = if(allowDBPath) ndoc.get.dropPrefix(LMFDB.dbPath) else None

    // if we have neither, we are not a valid theory
    if (suffixS.isEmpty && suffixD.isEmpty)
      return None

    // and get the db object
    Some(new DB(suffixS.getOrElse({suffixD.get}), mod.get))
  }
}
/**
  * Shared code for LMFDB Queries between the Storage and the backend
  */
trait LMFDBBackend {

  def debug(s : String) : Unit

  protected def toOML(json: JSONObject, db: DB, fields: List[DBField])(implicit controller: Controller): List[OML] = {
    fields map {case DBField(key, codec) =>
      val dfJ = json(key).getOrElse {
        throw BackendError("could not find JSON value " + key + " in \n" + json, db.schemaPath) //TODO use correct path
      }
      val df = codec.decode(dfJ)
      OML(LocalName(key), None, Some(df))
    }
  }

  //
  // GET OBJECTS FROM LMFDB
  //

  /**
    * Tries to retrieve json from a URL
    * @param url url to retrieve json from
    * @return
    */
  private def get_json(url: URI) : Option[JSON] = {
    val attempt = Try(io.Source.fromURL(url.toString))
    if (attempt.isFailure) None else {
      val json = Try(JSON.parse(attempt.get.toBuffer.mkString))
      if (json.isFailure) throw ParseError(url.toString).setCausedBy(json.failed.get)
      json.toOption
    }
  }

  /**
    * Retrieves JSON from a URL and recurses into a key called next until retrieval fails
    * @param url
    * @return
    */
  private def get_json_withnext(url : URI, next_key : String = "next") : List[JSON] = {

    // get the current page
    val data = get_json(url) match {
      case None => return Nil
      case Some(x) => x
    }

    // and iterate if possible
    data match {
      case j:JSONObject => j(next_key) match {
        case Some(JSONString(s)) =>
          val nurl = url.resolve(s)
          if(nurl != url){
            data :: get_json_withnext(url.resolve(s))
          } else {
            List(data)
          }

        case _ => List(data)
      }
      case _ => List(data)
    }
  }

  /** runs a mongo style lmfdb query */
  protected def lmfdbquery(db: String, query: JSONObject) : List[JSON] = {
    val queryParams = query.map.map(kv => {
      val key = kv._1.value
      val value = if(key.startsWith("_")){
        kv._2.asInstanceOf[JSONString].value
      } else {
        s"py${kv._2.toString}"
      }

      (key, value)
    })
    lmfdbquery(db, "&"+WebQuery.encode(queryParams))
  }

  /** runs a simple lmfdb query */
  protected def lmfdbquery(db:String, query:String) : List[JSON] = {
    // get the url
    val url = LMFDB.uri / s"$db?_format=json$query"

    debug(s"attempting to retrieve json from $url")

    // get all the data items
    get_json_withnext(url).flatMap(
      _.asInstanceOf[JSONObject]("data").get.asInstanceOf[JSONArray].values
    )
  }

  protected def findImplementor(schema : DeclaredTheory, forSymbol: GlobalName, err : String => Unit) : GlobalName = schema.getDeclarations.filter(d => {
    d.metadata.getValues(Metadata.implements).collect{
      case OMLIT(uri: URI, _) => uri
      // case UnknownOMLIT(uri : String,_) => URI(uri)
    }.contains(URI(forSymbol.toString))
  }).map(_.path).headOption.getOrElse({
    err(s"no implementor found for $forSymbol in ${schema.path}"); null
  })

  protected def getKey(schema : DeclaredTheory) : String = {
    def err(s: String) = throw BackendError(s,schema.path)
    // HACK HACK HACK; parse the ODK String
    val spath = OMS(Path.parseS("http://mathhub.info/MitM/Foundation?Strings?string", NamespaceMap.empty))

    schema.metadata.getValues(Metadata.key).headOption.getOrElse {
      err("metadata key 'key' not found in schema: " + schema.path); null
    } match {
      case StringLiterals(k : String) =>
        k // This no longer works; we are using the HACK below
      case UnknownOMLIT(a, `spath`) =>
        a.toString
      case s => err("metadata key 'key' is not a string in schema: " + schema.path); null
    }
  }

  def getTP(db : DB)(implicit controller: Controller) : DBTheory = getTP(db,db.schemaPath)

  private def getTP(db : DB, mp : MPath)(implicit controller: Controller) : DBTheory = {
    def err(s: String) = throw BackendError(s,mp)
    val th = Try(controller.getTheory(mp)).toOption match {
      case Some(t : DeclaredTheory) =>
        // db.addParent(t)
        t
      case _ =>
        err("Not a DeclaredTheory or Theory not found")
    }
    th.metadata.getValues(Metadata.implements).headOption.getOrElse {
      val ext = th.metadata.getValues(Metadata.extend).headOption match {
        case Some(OMMOD(mp2)) =>
          return getTP(db,mp2) + th
        case _ =>
          err("metadata key 'implements' or 'extends' not found in schema")
      }
    } match {
      case t: Term =>
        th.metadata.getValues(Metadata.constructor).headOption.getOrElse {
          err("metadata key 'constructor' not found in schema")
        } match {
          case t2: Term =>
            DBTheory(t,t2)
          case _ =>
            err("metadata key 'constructor'  found in schema")
        }
      case _ =>
        err("metadata key 'implements' or 'extends' not found in schema")
    }
  }
}



object LMFDBStore extends Storage with LMFDBBackend {

  def debug(s : String): Unit = {}

  def load(path: Path)(implicit controller: Controller) {

    val db = DB.fromPath(path, allowSchemaPath = false).getOrElse {
      throw NotApplicable("")
    }

    path match {
       case p: GlobalName => loadConstant(p, db)
       case mp : MPath =>
         val sch = Try(controller.getTheory(db.schemaPath)).toOption match {
           case Some(t : DeclaredTheory) => t
           case _ => throw NotApplicable("")
         }
        controller.library.getModules.find(_.path == mp).getOrElse {
          controller.add(Theory.empty(mp.parent,mp.name,sch.meta))
        }
    }
  }

  override def loadFragment(needed: Path, known: Path)(implicit controller: Controller) {
    load(needed)
  }

  private def loadConstant(path: GlobalName, db: DB)(implicit controller: Controller) {

    // find the type we are implementing, getting from records --> instance
    val dbT = getTP(db)

    val omls = dbT.parents.flatMap { case (ndb,s) =>
      val key = getKey(s)
      val fields = s.getConstants.flatMap {c =>
        c.metadata.getValues(Metadata.codec).headOption.toList.collect {
          case codecExp: Term =>
            DBField(c.name.toString, LMFDBCoder.buildCodec(codecExp))
        }
      }
      // TODO
      val query = "&" + key + "=" + path.name.toString
      val json = JSONObject(lmfdbquery(ndb.dbpath, query).flatMap { // TODO this flattening looks wrong
        case a: JSONObject => a.map
        case j => Nil
      })
      toOML(json,db,fields)
    }
    /*

    val fields = schema.getConstants.flatMap {c =>
       c.metadata.getValues(Metadata.codec).headOption.toList.collect {
         case codecExp: Term =>
            DBField(c.name.toString, LMFDBCoder.buildCodec(codecExp))
       }
    }

     val query = "&" + key + "=" + path.name.toString
     val json = JSONObject(lmfdbquery(db.dbpath, query).flatMap { // TODO this flattening looks wrong
        case a: JSONObject => a.map
        case j => Nil
      })
     val omls = toOML(json, db, fields)
     */

     val df = LFX.RecExp(omls : _*)

     val c = Constant(OMMOD(db.dbPath), path.name, Nil, Some(dbT.tp), Some(Apply(dbT.constructor,df)), None)
     controller.add(c)
  }
}

object LMFDBSystem extends VRESystem("lmfdb") with LMFDBBackend {//QueryExtension("lmfdb") with LMFDBBackend {
  val namespace = LMFDB.path

  def debug(s : String): Unit = log(s)

  private def error(msg: String) = {
    throw ImplementationError("LMFDB QueryEvaluator() failed to evaluate query. " + msg)
  }

  private def getAll(db : DB) : List[GlobalName] = {
    getSubjectTo(db, JSONObject())
  }

  /** retrieves a set of urls from a database that are subject to a given condition */
  private def getSubjectTo(db: DB, query : JSONObject) : List[GlobalName] = {
    // get the schema theory, this is a DeclaredTheory by precondition
    val schema = controller.get(db.schemaPath) match {
      case dt:DeclaredTheory => dt
      case _ => error("Schema-Theory missing from controller")
    }

    // get the key needed
    val key = getKey(schema)

    // build the query
    val queryJS = JSONObject(query.map :+ (JSONString("_fields"), JSONString(key)))

    // get a list of data items returned
    val datas : List[JSON] = lmfdbquery(db.dbpath, queryJS)

    // and now get a list of names
    val labels = datas.map({
      case o : JSONObject => o(key).getOrElse {
        error(s"Ill-formed JSON returned from database: Expected $key to be in returned objects, but only found ${o.map.map(_._1)}")
      }
      case u@_ => error(s"Ill-formed JSON returned from database: Expected return value to be an object, got $u")
    })

    // and return a list of globalnames in the db theory
    labels.map {
      case s : JSONString => db.dbPath ? s.value
      case _ => error("Ill-formed JSON returned from database: Expected labels to be a list of strings. ")
    }
  }

  /**
    * attempts to translate a query to an lmfdb query.
    *
    * This function does a partial evaluation, in the sense that every Related(..., ToObject(Declares)) query is evaluated.
    *
    * May throw an [[ImplementationError]] if the query can not be evaluated using lmfdb.
    *
    *
    * @param q
    * @param e
    * @param substiution
    * @return
    */
  private def translateQuery(q: Query, e:QueryEvaluator)(implicit substiution: QueryEvaluator.QuerySubstitution): (DB, JSONObject) = q match {
    case Related(to: Query, ToObject(Declares)) =>

      // get the db instance from the path
      val tPath = e.evalSinglePath(to) match {
        case m : MPath => m
        case _ => error("Ill-typed query: Expected ToObject(Declares) to give an MPath")
      }

      // get the right db
      val db = DB.fromPath(tPath, allowSchemaPath = false).getOrElse {
        error("Unable to assign lmfdb database. ")
      }

      (db, JSONObject())
    case Comprehension(domain: Query, varname: LocalName, pred: Prop) =>
      val (db, q) = translateQuery(domain, e)
      (db, translateProp(varname, pred, db))
    case _ =>
      error("Unable to translate query into an lmfdb query, evaluation failed")
  }

  /** translates a proposition (about a given variable assumed to be an lmfdb query) into an lmfdb query */
  private def translateProp(varname: LocalName, p : Prop, db:DB) : JSONObject = p match {
    case Holds(Bound(`varname`), Equals(q, l, r)) =>

      // match the symbol that is being applied
      val symbol = l match {
        case Apply(OMS(s), OMV(`q`)) => s
        case o@_ =>
          error(s"Unable to translate predicated into an lmfdb field selector. Expected LF Application, on the left hand side $o. ")
      }

      // find the symbol that is implemented by the operation
      val implementedSymbol = findImplementor(controller.getTheory(db.schemaPath), symbol, error)

      // the name of the field to look for
      val field = implementedSymbol.name.toPath

      // find the codec we are using for the value
      val codec = controller.getConstant(implementedSymbol).metadata.getValues(Metadata.codec).headOption.map {
        case codecExp: Term =>
          LMFDBCoder.buildCodec(codecExp)
      }.getOrElse(error(s"unable to find codec for $symbol, evaluation failed. "))

      // encode the actual value
      val value = codec.encode(r)

      // and make it a JSONObject
      JSONObject((field, value))
    case And(left, right) =>
      // and => we just join the query and all fields will need to be evaluated
      val lMap = translateProp(varname, left, db)
      val rMap = translateProp(varname, right, db)

      // quiet assumption: no double keys
      JSONObject.fromList(lMap.map ::: rMap.map)
    case o@_ =>
      error(s"Unable to translate predicate into an lmfdb query, unsupported predicated $o. ")
  }

  // translate a literal object into a json object
  private def term2JSON(tm : Term) : JSON = ???

  /**
    * Evaluates a Query using the lmfdb api
    * @param q           Query to evaluate
    * @param e           A QueryEvaluator to use for recursive queries
    * @param substiution Substiution (Context) to apply QueryEvaluation in
    * @return
    */
  def evaluate(q: Query, e: QueryEvaluator)(implicit substiution: QueryEvaluator.QuerySubstitution): HashSet[List[BaseType]] = {
    val (db, query) = translateQuery(q, e)
    ResultSet.fromElementList(getSubjectTo(db, query))
  }
}
