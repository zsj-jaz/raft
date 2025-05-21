error id: file://<WORKSPACE>/src/main/scala/raft/CandidateBehavior.scala:`<none>`.
file://<WORKSPACE>/src/main/scala/raft/CandidateBehavior.scala
empty definition using pc, found symbol in pc: `<none>`.
empty definition using semanticdb
empty definition using fallback
non-local guesses:

offset: 868
uri: file://<WORKSPACE>/src/main/scala/raft/CandidateBehavior.scala
text:
```scala
package raft

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import RaftNode._
import RaftHelpers._

object CandidateBehavior {

  def apply(node: RaftNode, votesReceived: Int = 1): Behavior[Command] = {
    Behaviors.withTimers[Command] { timers =>
      Behaviors.setup[Command] { context =>

        if (votesReceived == 1) {
          // Only do this once when candidate starts
          timers.startSingleTimer(ElectionTimeout, ElectionTimeout, node.randomElectionTimeout())
          startElection(node, context, votesReceived)
        }

        Behaviors.receiveMessage {
          
          case SetPeers(p, partition) =>
            node.peers = p.filterNot(_ == context.self)
            node.partition =
              if (partition.nonEmpty) partition.filterNot(_ == context.self)
              els@@e p.filterNot(_ == context.self)
            Behaviors.same

          case WriteRequest(_, _, _, replyTo) =>
            redirectClientToMostRecentLeader(node, replyTo)
            Behaviors.same

          case ReadRequest(_, _, _, replyTo) =>
            redirectClientToMostRecentLeader(node, replyTo)
            Behaviors.same

          case RequestVote(term, candidateId, lastLogIndex, lastLogTerm, replyTo) =>
            handleRequestVote(node, context, term, candidateId, lastLogIndex, lastLogTerm, replyTo)

          case VoteResponse(term, granted) =>
            handleVoteResponse(node, context, term, granted, votesReceived)

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
              term,
              leaderId,
              prevLogIndex,
              prevLogTerm,
              entries,
              leaderCommit,
              replyTo
            )

          case AppendEntriesResponse(term, _, _, _) =>
            handleUnexpectedAppendEntriesResponse(node, context, term)

          case GetState(replyTo) =>
            replyTo ! RaftState("Candidate", node.id, node.currentTerm, node.log)
            Behaviors.same

          case GetCommitIndex(replyTo) =>
            replyTo ! node.commitIndex
            Behaviors.same

          case ElectionTimeout =>
            context.log.info(s"[${node.id}] <Candidate> Election timeout! Restarting election.")
            node.candidate()

          case BecomeLeader =>
            node.leader()

          case _ => Behaviors.same
        }
      }
    }
  }

  private def startElection(
      node: RaftNode,
      context: ActorContext[Command],
      votesReceived: Int
  ): Unit = {
    node.setCurrentTerm(node.currentTerm + 1)
    node.setVotedFor(Some(node.id))

    val lastLogIndex = node.log.size - 1
    val lastLogTerm  = node.log(lastLogIndex).term

    context.log.info(
      s"[${node.id}] Starting election for term ${node.currentTerm} (lastLogIndex=$lastLogIndex, lastLogTerm=$lastLogTerm)"
    )

    val majority = (node.peers.size / 2) + 1

    if (votesReceived >= majority) {
      context.log.info(
        s"[${node.id}] Self-vote already gives majority — becoming leader immediately"
      )
      context.self ! BecomeLeader
    } else {
      node.partition.foreach { peer =>
        peer ! RequestVote(
          term = node.currentTerm,
          candidateId = node.id,
          lastLogIndex = lastLogIndex,
          lastLogTerm = lastLogTerm,
          replyTo = context.self
        )
      }

    }
  }

  private def handleVoteResponse(
      node: RaftNode,
      context: ActorContext[Command],
      term: Int,
      granted: Boolean,
      votesReceived: Int
  ): Behavior[Command] = {
    if (term > node.currentTerm) {
      context.log.info(s"[${node.id}] Stepping down: received VoteResponse with newer term $term")
      // votedfor = None before transit to follower
      stepdown(node, term) // No known leader in this case
      node.follower()
    } else if (term == node.currentTerm && granted) {
      val updatedVotes = votesReceived + 1
      val majority     = (node.peers.size + 1) / 2 + 1
      context.log.debug(s"[${node.id}] Got vote (total: $updatedVotes), majority is $majority")

      if (updatedVotes >= majority) {
        context.log.info(s"[${node.id}] Elected leader for term ${node.currentTerm}")
        // votedfor stay for voting for itself
        node.leader()
      } else {
        context.log.debug(s"[${node.id}] Still waiting for votes (total: $updatedVotes)")
        node.candidate(updatedVotes)
      }
    } else {
      Behaviors.same
    }
  }

  private def handleRequestVote(
      node: RaftNode,
      context: ActorContext[Command],
      term: Int,
      candidateId: String,
      lastLogIndex: Int,
      lastLogTerm: Int,
      replyTo: ActorRef[VoteResponse]
  ): Behavior[Command] = {
    if (term > node.currentTerm) {
      context.log.info(
        s"[${node.id}] Received RequestVote from $candidateId with newer term $term — stepping down"
      )
      // votedfor = None
      stepdown(node, term) // No known leader in this case
      transitionToFollowerAndReplay(
        node,
        RequestVote(term, candidateId, lastLogIndex, lastLogTerm, replyTo)
      )
    } else {
      context.log.info(
        s"[${node.id}] Denying vote request from $candidateId (term $term), current term is ${node.currentTerm}"
      )
      replyTo ! VoteResponse(node.currentTerm, voteGranted = false)
      Behaviors.same
    }
  }

  private def handleAppendEntries(
      node: RaftNode,
      context: ActorContext[Command],
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
        s"[${node.id}] Rejecting stale AppendEntries from $leaderId (term $term < ${node.currentTerm})"
      )
      rejectOldLeader(node, replyTo, context.self)
      Behaviors.same
    } else {
      context.log.info(
        s"[${node.id}] Stepping down due to AppendEntries from $leaderId (term $term >= ${node.currentTerm})"
      )
      // votedfor = None if term > currentTerm
      // votedfor stay the same if term == currentTerm
      stepdown(node, term, leaderId)
      transitionToFollowerAndReplay(
        node,
        AppendEntries(term, leaderId, prevLogIndex, prevLogTerm, entries, leaderCommit, replyTo)
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
        s"[${node.id}] <Candidate> Received unexpected AppendEntriesResponse with newer term $term — stepping down"
      )
      stepdown(node, term)
      node.follower()
    } else {
      context.log.debug(
        s"[${node.id}] <Candidate> Received unexpected AppendEntriesResponse — ignoring"
      )
      Behaviors.same
    }
  }
}

```


#### Short summary: 

empty definition using pc, found symbol in pc: `<none>`.