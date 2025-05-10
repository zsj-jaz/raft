// File: raft/RaftHelpers.scala
package raft

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import raft.RaftNode._
import raft.behaviors.FollowerBehavior

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

  def stepdown(node: RaftNode, term: Int, leaderId: String): Unit = {
    if (term > node.currentTerm) {
      node.setCurrentTerm(term)
      node.setVotedFor(None) // only reset if term increases to avoid double vote in the same term
    }
    if (leaderId != "") {
      node.setLeaderId(Some(leaderId))
    }

  }

  def replicateLog(
      node: RaftNode,
      prevLogIndex: Int,
      prevLogTerm: Int,
      entries: List[LogEntry],
      leaderCommit: Int
  ): (Boolean, Boolean) = {
    if (prevLogIndex >= 0) {
      if (prevLogIndex >= node.log.length) return (false, false)
      if (node.log(prevLogIndex).term != prevLogTerm) return (false, false)
    }

    var index    = prevLogIndex + 1
    var i        = 0
    var modified = false

    while (i < entries.length) {

      if (index >= node.log.length) {
        // reached the end of the follower's log, append the remaining new entries from the leader
        node.state.log = node.log ++ entries.drop(i)
        modified = true
        i = entries.length
      } else if (node.log(index).term != entries(i).term) {
        // found a mismatch, delete the entry and all that follow it, then append the new entries.
        node.state.log = node.log.take(index) ++ entries.drop(i)
        modified = true
        i = entries.length
      } else {
        // entries match, just walk by it.
        index += 1
        i += 1
      }
    }

    if (modified) {
      node.state.persist()
    }

    // if (leaderCommit > node.commitIndex) {
    //   node.commitIndex = math.min(leaderCommit, node.log.size - 1)
    //   applyCommittedEntries(node)
    // }

    /** The in the final step (#5) of min AppendEntries is necessary, and it needs to be computed
      * with the index of the last new entry. It is not sufficient to simply have the function that
      * applies things from your log between and lastApplied commitIndex stop when it reaches the
      * end of your log. This is because you may have entries in your log that differ from the
      * leader’s log after the entries that the leader sent you (which all match the ones in your
      * log). Because #3 dictates that you only truncate your log if you have conflicting entries,
      * those won’t be removed, and if leaderCommit is beyond the entries the leader sent you, you
      * may apply incorrect entries.*
      */

    val lastNewEntryIndex = prevLogIndex + entries.length
    val safeCommitIndex   = math.min(leaderCommit, lastNewEntryIndex)

    // apply after commitIndex updates, not just when new log entries are appended.
    if (safeCommitIndex > node.commitIndex) {
      node.commitIndex = safeCommitIndex
      applyCommittedEntries(node)
    }

    (true, modified)
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
    replyTo ! ClientResponse(success = false, message = message)
  }
}
