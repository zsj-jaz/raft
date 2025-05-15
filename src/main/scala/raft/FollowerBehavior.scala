package raft

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{Behaviors, TimerScheduler, ActorContext}
import RaftNode._
import RaftHelpers._

object FollowerBehavior {

  def apply(node: RaftNode): Behavior[Command] = {
    Behaviors.withTimers { timers =>
      // Start election timeout
      timers.startSingleTimer(ElectionTimeout, ElectionTimeout, node.randomElectionTimeout())

      Behaviors.receive { (context, message) =>
        message match {

          case ClientRequest(_, _, _, replyTo) =>
            redirectClientToMostRecentLeader(node, replyTo)
            Behaviors.same

          case ReadRequest(_, _, _, replyTo) =>
            redirectClientToMostRecentLeader(node, replyTo)
            Behaviors.same

          case SetPeers(p, partition) =>
            node.peers = p.filterNot(_ == context.self)
            node.partition =
              if (partition.nonEmpty) partition.filterNot(_ == context.self)
              else p.filterNot(_ == context.self)
            Behaviors.same

          case ElectionTimeout =>
            context.log.info(s"[${node.id}] <Follower> Timeout! Becoming candidate")
            node.candidate()

          case RequestVote(term, candidateId, lastLogIndex, lastLogTerm, replyTo) =>
            handleRequestVote(
              node,
              context,
              timers,
              term,
              candidateId,
              lastLogIndex,
              lastLogTerm,
              replyTo
            )

          case AppendEntriesResponse(term, _, _, _) =>
            handleUnexpectedAppendEntriesResponse(node, context, term)

          case AppendEntries(
                term,
                leaderId,
                prevLogIndex,
                prevLogTerm,
                entries,
                leaderCommit,
                replyTo
              ) =>
            handleAppendEntries(
              node,
              context,
              timers,
              term,
              leaderId,
              prevLogIndex,
              prevLogTerm,
              entries,
              leaderCommit,
              replyTo
            )

          case VoteResponse(term, _) =>
            handleUnexpectedVoteResponse(node, context, term)

          case GetState(replyTo) =>
            replyTo ! RaftState("Follower", node.id, node.currentTerm, node.log)
            Behaviors.same

          case GetCommitIndex(replyTo) =>
            replyTo ! node.commitIndex
            Behaviors.same

          case msg =>
            context.log.debug(s"[${node.id}] Follower received unknown or unhandled message: $msg")
            Behaviors.same
        }
      }
    }
  }

  private def handleRequestVote(
      node: RaftNode,
      context: ActorContext[Command],
      timers: TimerScheduler[Command],
      term: Int,
      candidateId: String,
      lastLogIndex: Int,
      lastLogTerm: Int,
      replyTo: ActorRef[VoteResponse]
  ): Behavior[Command] = {
    if (term < node.currentTerm) {
      context.log.info(
        s"[${node.id}] <Follower> Rejecting vote for $candidateId: stale term $term < ${node.currentTerm}"
      )
      replyTo ! VoteResponse(node.currentTerm, voteGranted = false)
      Behaviors.same
    } else {
      if (term > node.currentTerm) {
        node.setCurrentTerm(term)
        node.setVotedFor(None)
      }

      // if myLastIndex == 0, myLastTerm == 0 (dummy entry)
      val myLastIndex = node.log.size - 1
      val myLastTerm  = node.log(myLastIndex).term

      val upToDate =
        (lastLogTerm > myLastTerm) ||
          (lastLogTerm == myLastTerm && lastLogIndex >= myLastIndex)

      val notVotedOrVotedForYou = node.votedFor.isEmpty || node.votedFor.contains(candidateId)

      if (notVotedOrVotedForYou && upToDate) {
        context.log.info(s"[${node.id}] <Follower> Granting vote to $candidateId for term $term")
        node.setVotedFor(Some(candidateId))
        // only reset timer if grant vote
        timers.startSingleTimer(ElectionTimeout, ElectionTimeout, node.randomElectionTimeout())
        replyTo ! VoteResponse(term, voteGranted = true)
      } else {
        context.log.info(
          s"[${node.id}] <Follower> Denying vote to $candidateId (votedFor = ${node.votedFor}, upToDate = $upToDate)"
        )
        // not reset timer since we are not granting vote
        replyTo ! VoteResponse(term, voteGranted = false)
      }
      Behaviors.same
    }
  }

  private def handleUnexpectedVoteResponse(
      node: RaftNode,
      context: ActorContext[Command],
      term: Int
  ): Behavior[Command] = {
    if (term > node.currentTerm) {
      context.log.info(
        s"[${node.id}] <Follower> Received unexpected VoteResponse with newer term $term — stepping down"
      )
      stepdown(node, term)
    } else {
      context.log.debug(
        s"[${node.id}] <Follower> Received unexpected VoteResponse (term $term) — ignoring"
      )
    }
    Behaviors.same
  }

  private def handleAppendEntries(
      node: RaftNode,
      context: ActorContext[Command],
      timers: TimerScheduler[Command],
      term: Int,
      leaderId: String,
      prevLogIndex: Int,
      prevLogTerm: Int,
      entries: List[LogEntry],
      leaderCommit: Int,
      replyTo: ActorRef[AppendEntriesResponse]
  ): Behavior[Command] = {

    context.log.info(
      s"[${node.id}] <Follower> Received AppendEntries from $leaderId: term=$term, prevLogIndex=$prevLogIndex, prevLogTerm=$prevLogTerm, entries=${entries
          .map(_.command)}"
    )

    if (term < node.currentTerm) {
      context.log.debug(
        s"[${node.id}] <Follower> Rejecting stale AppendEntries from $leaderId (term $term < ${node.currentTerm})"
      )
      rejectOldLeader(node, replyTo, context.self)
    } else {
      // votedfor stay the same if term == currentTerm
      // votedfor = None if term > currentTerm
      stepdown(node, term, leaderId)
      // Reset election timeout when receiving AppendEntries RPC from current leader
      timers.startSingleTimer(ElectionTimeout, ElectionTimeout, node.randomElectionTimeout())

      val (success, modified) = replicateLog(node, prevLogIndex, prevLogTerm, entries, leaderCommit)

      val matchIndexIfSuccess = if (success) {
        if (entries.nonEmpty && modified) {
          context.log.info(
            s"[${node.id}] <Follower> Appended ${entries.length} entries from leader $leaderId"
          )
          context.log.info(
            s"[${node.id}] <Follower> log terms: ${node.log.map(_.term).mkString("[", ", ", "]")}"
          )
        }
        prevLogIndex + entries.length
      } else {
        context.log.info(
          s"[${node.id}] <Follower> Log inconsistency with leader at index $prevLogIndex"
        )
        -1
      }

      replyTo ! AppendEntriesResponse(
        term = node.currentTerm,
        success = success,
        sender = context.self,
        matchIndexIfSuccess = matchIndexIfSuccess
      )

      Behaviors.same
    }

  }

  private def replicateLog(
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
        // end of loop
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
    }

    applyCommittedEntries(node)

    (true, modified)
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

  private def handleUnexpectedAppendEntriesResponse(
      node: RaftNode,
      context: ActorContext[Command],
      term: Int
  ): Behavior[Command] = {
    if (term > node.currentTerm) {
      context.log.info(
        s"[${node.id}] <Follower> Received unexpected AppendEntriesResponse with newer term $term — stepping down"
      )
      stepdown(node, term)
    } else {
      context.log.debug(
        s"[${node.id}] <Follower> Received unexpected AppendEntriesResponse (term $term) — ignoring"
      )
    }
    Behaviors.same
  }
}
