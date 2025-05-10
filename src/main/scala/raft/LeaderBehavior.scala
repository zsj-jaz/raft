package raft.behaviors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{Behaviors, TimerScheduler, ActorContext}
import raft.RaftNode
import raft.RaftNode._
import raft.RaftHelpers._

import scala.concurrent.duration._

object LeaderBehavior {

  def apply(node: RaftNode): Behavior[Command] = {
    Behaviors.withTimers { timers =>
      // Send heartbeats every 100ms
      timers.startTimerWithFixedDelay("heartbeat", SendHeartbeat, 50.millis)

      // Initialize matchIndex and nextIndex
      // re-initialize matchIndex and nextIndex after election
      node.matchIndex = node.peers.map(p => p -> 0).toMap // zero
      node.nextIndex =
        node.peers.map(p => p -> node.log.size).toMap // last log index + 1 = log.size

      Behaviors.setup { context =>
        context.log.info(s"[${node.id}] Became Leader in term ${node.currentTerm}")

        val noopEntry = LogEntry(
          term = node.currentTerm,
          command = "<no-op>",
          clientId = "",
          serialNum = -1,
          replyTo = None
        )

        node.state.log = node.log :+ noopEntry
        node.state.persist()

        sendAppendEntries(node, context) // replicate the no-op immediately

        Behaviors.receiveMessage {
          case ClientRequest(command, clientId, serialNum, replyTo) =>
            handleClientRequest(node, context, command, clientId, serialNum, replyTo)

          case SendHeartbeat =>
            sendAppendEntries(node, context)
            Behaviors.same

          case GetState(replyTo) =>
            replyTo ! RaftState("Leader", node.currentTerm)
            Behaviors.same

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

          case AppendEntriesResponse(term, success, sender, matchIndexIfSuccess) =>
            handleAppendEntriesResponse(node, context, term, success, sender, matchIndexIfSuccess)

          case RequestVote(term, candidateId, lastLogIndex, lastLogTerm, replyTo) =>
            handleRequestVote(node, context, term, candidateId, lastLogIndex, lastLogTerm, replyTo)

          case msg =>
            context.log.debug(s"[${node.id}] <Leader> Received unknown or unhandled message: $msg")
            Behaviors.same
        }
      }
    }
  }

  private def handleClientRequest(
      node: RaftNode,
      context: ActorContext[Command],
      command: String,
      clientId: String,
      serialNum: Int,
      replyTo: ActorRef[ClientResponse]
  ): Behavior[Command] = {
    context.log.info(
      s"[${node.id}] <Leader> Received ClientRequest ($command, $clientId, $serialNum)"
    )

    val entry = LogEntry(
      term = node.currentTerm,
      command = command,
      clientId = clientId,
      serialNum = serialNum,
      replyTo = Some(replyTo)
    )

    node.state.log = node.log :+ entry
    node.state.persist()

    sendAppendEntries(node, context) // trigger replication
    Behaviors.same
  }

  private def sendAppendEntries(node: RaftNode, context: ActorContext[Command]): Unit = {
    node.peers.foreach { peer =>
      val nextIdx       = node.nextIndex.getOrElse(peer, node.log.size) // fallback for safety
      val prevLogIndex  = nextIdx - 1
      val prevLogTerm   =
        if (prevLogIndex < node.log.length) node.log(prevLogIndex).term else 0
      val entriesToSend = node.log.slice(nextIdx, node.log.size)

      if (entriesToSend.isEmpty) {
        context.log.debug(s"[${node.id}] <Leader> Sending heartbeat to ${peer.path.name}")
      } else {
        context.log.debug(
          s"[${node.id}] <Leader> Sending ${entriesToSend.length} entries to ${peer.path.name}"
        )
      }

      peer ! AppendEntries(
        term = node.currentTerm,
        leaderId = node.id,
        prevLogIndex = prevLogIndex,
        prevLogTerm = prevLogTerm,
        entries = entriesToSend,
        leaderCommit = node.commitIndex,
        replyTo = context.self
      )
    }
  }

  private def handleAppendEntries(
      node: RaftNode,
      context: akka.actor.typed.scaladsl.ActorContext[Command],
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
        s"[${node.id}] <Leader> Rejecting stale AppendEntries from $leaderId (term $term < ${node.currentTerm})"
      )
      rejectOldLeader(node, replyTo, context.self)
    } else {
      context.log.info(
        s"[${node.id}] <Leader> Stepping down due to AppendEntries from $leaderId (term $term >= ${node.currentTerm})"
      )
      // votedfor = None if term > currentTerm
      // votedfor = None if term == currentTerm
      stepdown(node, term, leaderId)
      return transitionToFollowerAndReplay(
        node,
        AppendEntries(term, leaderId, prevLogIndex, prevLogTerm, entries, leaderCommit, replyTo)
      )
    }
  }

  private def handleAppendEntriesResponse(
      node: RaftNode,
      context: ActorContext[Command],
      term: Int,
      success: Boolean,
      sender: ActorRef[Command],
      matchIndexIfSuccess: Int
  ): Behavior[Command] = {
    if (term > node.currentTerm) {
      context.log.info(
        s"[${node.id}] <Leader> AppendEntriesResponse term $term > currentTerm ${node.currentTerm}, stepping down"
      )
      // votedfor = None if term > currentTerm
      stepdown(node, term, leaderId = "")
      return node.follower()
    }

    if (term == node.currentTerm && success) {
      val follower = sender
      node.matchIndex = node.matchIndex.updated(follower, matchIndexIfSuccess)
      node.nextIndex = node.nextIndex.updated(follower, matchIndexIfSuccess + 1)
      context.log.debug(s"[${node.id}] <Leader> Updated nextIndex and matchIndex for follower")
      tryAdvanceCommitIndex(node, context)
    }

    if (term == node.currentTerm && !success) {
      val follower     = sender
      val oldNextIndex = node.nextIndex.getOrElse(follower, node.log.size)
      val newNextIndex = math.max(1, oldNextIndex - 1) // 1-indexed
      node.nextIndex = node.nextIndex.updated(follower, newNextIndex)

      context.log.debug(
        s"[${node.id}] <Leader> AppendEntries failed, backing off nextIndex for follower: $oldNextIndex → $newNextIndex"
      )
    }

    Behaviors.same
  }

  private def tryAdvanceCommitIndex(node: RaftNode, context: ActorContext[Command]): Unit = {
    val matchIndexes  = node.matchIndex.values.toList :+ (node.log.size - 1) // include leader
    val sorted        = matchIndexes.sorted
    val majorityIndex = sorted((node.peers.size) / 2)

    if (
      majorityIndex > node.commitIndex &&
      node.log.isDefinedAt(majorityIndex) &&
      node.log(majorityIndex).term == node.currentTerm
      // Only allow committing an entry if it's from the current term (Raft §5.4.2).
      // This prevents a new leader from incorrectly committing entries from older terms.
    ) {
      node.commitIndex = majorityIndex
      context.log.info(s"[${node.id}] <Leader> Commit index advanced to $majorityIndex")
    }
    applyCommittedEntriesAndRespondClient(node)
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
      // votedfor = None if term > currentTerm
      stepdown(node, term, leaderId = "") // unknown leader
      return transitionToFollowerAndReplay(
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

}
