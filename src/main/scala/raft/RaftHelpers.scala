// File: raft/RaftHelpers.scala
package raft

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
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
      node.setCurrentTerm(term)
      node.setVotedFor(None) // only reset if term increases to avoid double vote in the same term
    }
    leaderIdOpt.foreach(id => node.setLeaderId(Some(id)))
  }

  def applyCommittedEntriesAndRespondClient(node: RaftNode): Unit = {
    while (node.lastApplied < node.commitIndex) {
      node.lastApplied += 1
      val entry = node.log(node.lastApplied)
      println(s"[${node.id}] Applying log[${node.lastApplied}]: ${entry.command}")

      val (applied, clientResponse) = node.stateMachine.applyCommand(
        entry.command,
        entry.clientId,
        entry.serialNum
      )

      entry.replyTo.foreach(_ ! clientResponse)

    }
  }

  def applyCommittedEntries(node: RaftNode): Unit = {
    while (node.lastApplied < node.commitIndex) {
      node.lastApplied += 1
      val entry = node.log(node.lastApplied)
      println(s"[${node.id}] Applying log[${node.lastApplied}]: ${entry.command}")

      node.stateMachine.applyCommand(
        entry.command,
        entry.clientId,
        entry.serialNum
      )

    }
  }

  def transitionToFollowerAndReplay(
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

  def redirectClientToMostRecentLeader(
      node: RaftNode,
      replyTo: ActorRef[ClientResponse]
  ): Unit = {
    val message = node.leaderId match {
      case Some(id) => s"Redirect to leader $id"
      case None     => "Leader unknown"
    }
    println(s"[${node.id}] Redirecting client: $message")
    replyTo ! ClientResponse(success = false, message = message)
  }

  def handleUnstableRead(
      node: RaftNode,
      key: String,
      replyTo: ActorRef[ClientResponse]
  ): Unit = {
    node.stateMachine match {
      case fsm: FileAppendingStateMachine =>
        val value = fsm.tentativeRead(key)
        replyTo ! ClientResponse(success = true, message = value)
      case _                              =>
        replyTo ! ClientResponse(success = false, message = "Unsupported state machine")
    }
  }
}
