// File: raft/RaftHelpers.scala
package raft

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import scala.annotation.targetName
import RaftNode._

object RaftHelpers {

  def rejectOldLeader(
      node: RaftNode,
      replyTo: ActorRef[AppendEntriesResponse],
      sender: ActorRef[Command]
  ): Behavior[Command] = {
    replyTo ! AppendEntriesResponse(
      term = node.currentTerm,
      success = false,
      sender = sender,
      matchIndexIfSuccess = -1
    )
    Behaviors.same
  }

  def stepdown(node: RaftNode, term: Int): Unit =
    stepdown(node, term, None)

  def stepdown(node: RaftNode, term: Int, leaderId: String): Unit =
    stepdown(node, term, Some(leaderId))

  private def stepdown(node: RaftNode, term: Int, leaderIdOpt: Option[String]): Unit = {
    if (term > node.currentTerm) {
      node.persistCurrentTerm(term)
      node.persistVotedFor(
        None
      ) // only reset if term increases to avoid double vote in the same term
    }
    leaderIdOpt.foreach(id => node.setLeaderId(Some(id)))
  }

  def transitToFollowerAndReplay(
      node: RaftNode,
      message: Command
  ): Behavior[Command] = {
    Behaviors.withTimers { _ =>
      val follower = FollowerBehavior(node)
      Behaviors.setup { context =>
        context.self ! message
        follower
      }
    }
  }

  @targetName("redirectWriteClient")
  def redirectClientToMostRecentLeader(node: RaftNode, replyTo: ActorRef[WriteResponse]): Unit = {
    val leaderHint = node.leaderId.getOrElse("unknown")
    replyTo ! WriteResponse(success = false, message = s"Redirect to leader $leaderHint")
  }

  @targetName("redirectReadClient")
  def redirectClientToMostRecentLeader(node: RaftNode, replyTo: ActorRef[ReadResponse]): Unit = {
    val leaderHint = node.leaderId.getOrElse("unknown")
    replyTo ! ReadResponse(s"Redirect to leader $leaderHint")
  }

}
