error id: raft/behaviors/`<import>`.
file://<WORKSPACE>/src/main/scala/raft/FollowerBehavior.scala
empty definition using pc, found symbol in pc: raft/behaviors/`<import>`.
empty definition using semanticdb
empty definition using fallback
non-local guesses:

offset: 149
uri: file://<WORKSPACE>/src/main/scala/raft/FollowerBehavior.scala
text:
```scala
package raft.behaviors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{Behaviors, TimerScheduler, ActorContext}
impor@@t raft.RaftNode
import raft.RaftNode._
import raft.RaftHelpers._

object FollowerBehavior {

  def apply(node: RaftNode): Behavior[Command] = {
    Behaviors.withTimers { timers =>
      // ⏱ Start election timeout
      timers.startSingleTimer(ElectionTimeout, ElectionTimeout, node.randomElectionTimeout())

      Behaviors.receive { (context, message) =>
        message match {

          case SetPeers(p) =>
            node.peers = p
            Behaviors.same

          case ElectionTimeout =>
            context.log.info(s"[${node.id}] Timeout! Becoming candidate")
            node.candidate()

          case RequestVote(term, candidateId, replyTo) =>
            handleRequestVote(node, context, timers, term, candidateId, replyTo)

          case AppendEntries(term, leaderId, prevLogIndex, prevLogTerm, entries, leaderCommit, replyTo) =>
            handleAppendEntries(node, context, timers, term, leaderId, prevLogIndex, prevLogTerm, entries, leaderCommit, replyTo)

          case VoteResponse(_, _) =>
            Behaviors.same  // Followers ignore VoteResponses

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
    replyTo: ActorRef[VoteResponse]
  ): Behavior[Command] = {
    if (term >= node.currentTerm && node.votedFor.isEmpty) {
      context.log.info(s"[${node.id}] Voting for $candidateId in term $term")
      node.setCurrentTerm(term)
      node.setVotedFor(Some(candidateId))
      timers.startSingleTimer(ElectionTimeout, ElectionTimeout, node.randomElectionTimeout())
      replyTo ! VoteResponse(term, voteGranted = true)
      node.follower()
    } else {
      context.log.info(s"[${node.id}] Denying vote to $candidateId (term $term), already voted or stale term")
      replyTo ! VoteResponse(node.currentTerm, voteGranted = false)
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
      context.log.debug(s"[${node.id}] Rejecting stale AppendEntries from $leaderId (term $term < ${node.currentTerm})")
      rejectOldLeader(node, replyTo)
    } else {
      if (term > node.currentTerm) {
        context.log.debug(s"[${node.id}] Updating term and leaderId due to AppendEntries from $leaderId (term $term > ${node.currentTerm})")
        stepdown(node, term, leaderId)
      }

      timers.startSingleTimer(ElectionTimeout, ElectionTimeout, node.randomElectionTimeout())

      val success = replicateLog(node, prevLogIndex, prevLogTerm, entries, leaderCommit)

      if (!success) {
        context.log.info(s"[${node.id}] Log inconsistency with leader at index $prevLogIndex")
      } else if (entries.nonEmpty) {
        context.log.info(s"[${node.id}] Appended ${entries.length} entries from leader $leaderId")
      }

      replyTo ! AppendEntriesResponse(term = node.currentTerm, success = success)
    }

    Behaviors.same
  }
}

```


#### Short summary: 

empty definition using pc, found symbol in pc: raft/behaviors/`<import>`.