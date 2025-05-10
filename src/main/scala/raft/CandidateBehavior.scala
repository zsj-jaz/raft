package raft.behaviors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import raft.RaftNode
import raft.RaftNode._
import raft.RaftHelpers._

object CandidateBehavior {

  def apply(node: RaftNode, votesReceived: Int = 1): Behavior[Command] = {
    Behaviors.withTimers { timers =>
      Behaviors.setup { context =>
        if (votesReceived == 1) {
          timers.startSingleTimer(ElectionTimeout, ElectionTimeout, node.randomElectionTimeout())
          startElection(node, context)
        }

        Behaviors.receiveMessage {

          case ClientRequest(_, _, _, replyTo) =>
            redirectClientToMostRecentLeader(node, replyTo)
            Behaviors.same

          case SetPeers(p) =>
            node.peers = p
            Behaviors.same

          case ElectionTimeout =>
            context.log.info(s"[${node.id}] <Candidate> Election timeout! Restarting election.")
            node.candidate()

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

          case GetState(replyTo) =>
            replyTo ! RaftState("Candidate", node.currentTerm)
            Behaviors.same

          case _ => Behaviors.same
        }
      }
    }
  }

  private def startElection(node: RaftNode, context: ActorContext[Command]): Unit = {
    node.setCurrentTerm(node.currentTerm + 1)
    node.setVotedFor(Some(node.id))

    // if lastLogIndex == 0, lastLogTerm = 0 (dummy entry)
    val lastLogIndex = node.log.size - 1
    val lastLogTerm  = node.log(lastLogIndex).term

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
  ): Behavior[Command] = {
    if (term > node.currentTerm) {
      context.log.info(s"[${node.id}] Stepping down: received VoteResponse with newer term $term")
      // votedfor = None before transit to follower
      stepdown(node, term, leaderId = "")
      node.follower()
    } else if (term == node.currentTerm && granted) {
      val updatedVotes = votesReceived + 1
      val majority     = (node.peers.size + 1) / 2 + 1
      context.log.debug(s"[${node.id}] Got vote (total: $updatedVotes), majority is $majority")

      if (updatedVotes >= majority) {
        context.log.info(s"[${node.id}] 🎉 Elected leader for term ${node.currentTerm}")
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
      stepdown(node, term, leaderId = "") // No known leader in this case
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
}
