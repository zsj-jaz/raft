error id: 
file://<WORKSPACE>/src/main/scala/raft/CandidateBehavior.scala
empty definition using pc, found symbol in pc: 
empty definition using semanticdb

found definition using fallback; symbol CandidateBehavior
offset: 674
uri: file://<WORKSPACE>/src/main/scala/raft/CandidateBehavior.scala
text:
```scala
package raft.behaviors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import raft.RaftNode
import raft.RaftNode._
import raft.RaftHelpers._

object CandidateBehavior {

  def apply(node: RaftNode, votesReceived: Int = 1): Behavior[Command] = {
    Behaviors.setup { context =>
      if (votesReceived == 1)
        startElection(node, context)

      Behaviors.receiveMessage {
        case SetPeers(p) =>
          node.peers = p
          Behaviors.same

        case VoteResponse(term, granted) =>
          votesReceived = handleVoteResponse(node, context, term, granted, votesReceived)
          CandidateBehavi@@or(node, updatedVotes)

        case RequestVote(term, candidateId, replyTo) =>
          handleRequestVote(node, context, term, candidateId, replyTo)

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

        case GetState(replyTo) =>
          replyTo ! RaftState("Candidate", node.currentTerm)
          Behaviors.same

        case _ => Behaviors.same
      }
    }
  }

  private def startElection(
      node: RaftNode,
      context: ActorContext[Command]
  ): Unit = {
    node.setCurrentTerm(node.currentTerm + 1)
    node.setVotedFor(Some(node.id))

    // First election: RequestVote(term = 1, candidateId = "X", lastLogIndex = -1, lastLogTerm = -1)
    val lastLogIndex = node.log.size - 1
    val lastLogTerm  = if (lastLogIndex >= 0) node.log(lastLogIndex).term else -1

    context.log.info(
      s"[${node.id}] Starting election for term ${node.currentTerm} (lastLogIndex=$lastLogIndex, lastLogTerm=$lastLogTerm)"
    )

    node.peers.foreach { peer =>
      peer ! RequestVote(
        term = node.currentTerm,
        candidateId = node.id,
        lastLogIndex = lastLogIndex,
        lastLogTerm = lastLogTerm,
        replyTo = context.self
      )
    }
  }

  private def handleVoteResponse(
      node: RaftNode,
      context: ActorContext[Command],
      term: Int,
      granted: Boolean,
      votesReceived: Int
  ): Int = {
    if (term == node.currentTerm && granted) {
      val updatedVotes = votesReceived + 1
      context.log.info(s"[${node.id}] Got vote (total: $updatedVotes)")
      val majority     = (node.peers.size + 1) / 2 + 1
      if (updatedVotes >= majority) {
        context.log.info(s"[${node.id}] Elected as leader!")
        node.leader()
      }
      updatedVotes
    } else {
      votesReceived
    }
  }

  private def handleRequestVote(
      node: RaftNode,
      context: ActorContext[Command],
      term: Int,
      candidateId: String,
      replyTo: ActorRef[VoteResponse]
  ): Behavior[Command] = {
    if (term > node.currentTerm) {
      node.setCurrentTerm(term)
      node.setVotedFor(Some(candidateId))
      context.log.info(
        s"[${node.id}] Stepping down to follower and voting for $candidateId (term $term)"
      )
      replyTo ! VoteResponse(term, voteGranted = true)
      node.follower()
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

    } else {
      context.log.info(
        s"[${node.id}] Stepping down due to AppendEntries from $leaderId (term $term >= ${node.currentTerm})"
      )
      stepdown(node, term, leaderId)
      return transitionToFollowerAndReplay(
        node,
        AppendEntries(term, leaderId, prevLogIndex, prevLogTerm, entries, leaderCommit, replyTo)
      )
    }
  }
}

```


#### Short summary: 

empty definition using pc, found symbol in pc: 