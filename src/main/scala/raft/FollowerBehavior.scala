package raft.behaviors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{Behaviors, TimerScheduler, ActorContext}
import raft.RaftNode
import raft.RaftNode._
import raft.RaftHelpers._

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

          case SetPeers(p) =>
            node.peers = p
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

          case GetState(replyTo) =>
            replyTo ! RaftState("Follower", node.currentTerm)
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
}
