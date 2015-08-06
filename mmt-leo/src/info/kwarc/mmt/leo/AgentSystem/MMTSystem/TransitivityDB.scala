package info.kwarc.mmt.leo.AgentSystem.MMTSystem

import scalax.collection.GraphTraversal.Predecessors
import scalax.collection.edge.{LDiEdge, LUnDiEdge}
import scalax.collection.mutable.Graph

/**
 * Created by Mark on 8/3/2015.
 *
 * this class holds the transitivity database which
 * stores a graph of MMTTerms related by a
 * transitive relation. All terms are labeled
 * with their respective goals to ensure soundness
 */

class TransitivityDB {
  
  protected val graph:Graph[TermEntry,LUnDiEdge] = Graph().asInstanceOf[Graph[TermEntry,LUnDiEdge]]

  def getGraph = graph
  val directed = true
  
  def add(t1:TermEntry,t2:TermEntry):Unit = {
    if (t1==t2) return
    var g:Goal = t1.goal

    if (t1.goal.isAbove(t2.goal)){
      g=t2.goal
    }

    if (directed) {
      graph += LDiEdge(t1, t2)(g)
    }else{
      graph += LUnDiEdge(t1,t2)(g)
    }
  }
  //TODO add a proof getter aka get a list of facts that result in the proof when applied

  def add(l: List[(TermEntry,TermEntry)]):Unit = l.foreach(e=>add(e._1,e._2))
  def add(l: (TermEntry,TermEntry)*):Unit = l.foreach(e=>add(e._1,e._2))

  def n(outer: TermEntry) = graph get outer

  def compare(a:TermEntry,b:TermEntry):Option[Boolean] = {
    if (n(a).withSubgraph(nodes = _.goal.isAbove(a.goal),edges = isEdgeAboveNode(_,a)).pathTo(n(b)).isDefined){
      return Some(true)
    }
    None
  }

  override def toString:String = graph.toString()


  def getSubGraph(n:TermEntry):Graph[TermEntry,LUnDiEdge] = {
    graph filter graph.having(node = _.goal.isAbove(n.goal),edge = isEdgeAboveNode(_,n))
  }


  type NT = graph.NodeT
  type ET = graph.EdgeT

  /** Determines if the goal of an edge is above the goal of a node*/
  private def isEdgeAboveNode(e:ET,n:TermEntry)= {
    e.label match {
      case goal: Goal =>
        goal.isAbove(n.goal)
      case _ => true
    }
  }


  def transClosureOf(t1:TermEntry) = {
    val subGraph = getSubGraph(t1)
    (subGraph get t1).outerNodeTraverser.toSet
  }

  def dualTransClosureOf(t1:TermEntry)= {
    val subGraph = getSubGraph(t1)
    (subGraph get t1).outerNodeTraverser.withDirection(Predecessors).toSet
  }


}

class EqualityDB extends TransitivityDB {
  override val directed=false
}

