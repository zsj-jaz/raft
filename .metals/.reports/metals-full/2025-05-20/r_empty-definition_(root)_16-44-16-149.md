error id: file://<WORKSPACE>/src/main/scala/raft/LeaderBehavior.scala:`<none>`.
file://<WORKSPACE>/src/main/scala/raft/LeaderBehavior.scala
empty definition using pc, found symbol in pc: `<none>`.
empty definition using semanticdb
empty definition using fallback
non-local guesses:

offset: 4107
uri: file://<WORKSPACE>/src/main/scala/raft/LeaderBehavior.scala
text:
```scala
package raft

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{Behaviors, TimerScheduler, ActorContext}
import RaftNode._
import RaftHelpers._
import scala.concurrent.duration._

object LeaderBehavior {

  def apply(node: RaftNode): Behavior[Command] = {
    Behaviors.withTimers { timers =>
      timers.startTimerWithFixedDelay("heartbeat", SendHeartbeat, 50.millis)
      setup(node, timers)
    }
  }

  private def setup(
      node: RaftNode,
      timers: TimerScheduler[Command]
  ): Behavior[Command] = {
    Behaviors.setup { context =>
      context.log.info(s"[${node.id}] Became Leader in term ${node.currentTerm}")

      var pendingReads: List[(String, String, Int, ActorRef[ClientResponse])]  = List.empty
      var readQuorumAcks: Set[ActorRef[Command]]                               = Set.empty
      var pendingClientAcks: Map[Int, (String, Int, ActorRef[ClientResponse])] = Map.empty

      val noopEntry = LogEntry(
        term = node.currentTerm,
        command = "<no-op>",
        clientId = "",
        serialNum = -1,
        replyTo = None
      )

      node.state.log = node.log :+ noopEntry
      node.state.persist()
      context.log.info(
        s"[${node.id}] <Leader> log terms: ${node.log.map(_.term).mkString("[", ", ", "]")}"
      )

      // Initialize matchIndex and nextIndex
      // re-initialize matchIndex and nextIndex after election
      var matchIndex = node.peers.map(p => p.path.name -> 0).toMap // zero
      var nextIndex  =
        node.peers.map(p => p.path.name -> node.log.size).toMap // last log index + 1 = log.size

      sendAppendEntries() // replicate the no-op immediately, for read request

      def handleWriteRequest(
          command: String,
          clientId: String,
          serialNum: Int,
          replyTo: ActorRef[ClientResponse]
      ): Behavior[Command] = {
        context.log.info(
          s"[${node.id}] <Leader> Received ClientRequest ($command, $clientId, $serialNum)"
        )

        val logIndex = node.log.size // this will be the index after append

        val entry = LogEntry(
          term = node.currentTerm,
          command = command,
          clientId = clientId,
          serialNum = serialNum,
          replyTo = Some(replyTo)
        )

        pendingClientAcks += (logIndex -> (clientId, serialNum, replyTo))
        node.state.log = node.log :+ entry
        node.state.persist()
        context.log.info(
          s"[${node.id}] <Leader> log terms: ${node.log.map(_.term).mkString("[", ", ", "]")}"
        )

        sendAppendEntries()     // trigger replication
        tryAdvanceCommitIndex() // handle single-node case
        Behaviors.same
      }

      def handleReadRequest(
          key: String,
          clientId: String,
          serialNum: Int,
          replyTo: ActorRef[ClientResponse]
      ): Unit = {

        context.log.info(
          s"[${node.id}] <Leader> Received ReadRequest ($key, $clientId, $serialNum)"
        )

        pendingReads = pendingReads :+ ((key, clientId, serialNum, replyTo))

        context.log.debug(
          s"[${node.id}] <Leader> Pending reads buffer now has: ${pendingReads
              .map { case (k, c, s, _) => s"($k, $c, $s)" }
              .mkString(", ")}"
        )

        sendAppendEntries() // in case the leadership is stale
        context.log.debug(
          s"[${node.id}] <Leader> Sending AppendEntries for read request to followers"
        )

      }

      def sendAppendEntries(): Unit = {
        node.partition.foreach { peer =>
          val nextIdx       = nextIndex.getOrElse(peer.path.name, node.log.size) // fallback for safety
          context.log.info(
            s"[${node.id}] <Leader> Sending AppendEntries to ${peer.path.name} (nextIdx=$nextIdx)"
          )
          val prevLogIndex  = nextIdx - 1
          val prevLogTerm   =
            if (prevLogIndex < node.log.length) node.log(prevLogIndex).term else 0
          val entriesToSend = node.log.slice(nextIdx, node.log.size)

          if (entriesToSend.isEmpty) {
            // context.log.debug(s@@"[${node.id}] <Leader> Sending heartbeat to ${peer.path.name}")
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

      def handleAppendEntries(
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
            s"[${node.id}] <Leader> Rejecting stale AppendEntries from $leaderId (term $term < ${node.currentTerm})"
          )
          rejectOldLeader(node, replyTo, context.self)
        } else {
          context.log.info(
            s"[${node.id}] <Leader> Stepping down due to AppendEntries from $leaderId (term $term >= ${node.currentTerm})"
          )
          // votedfor = None if term > currentTerm
          // step down even if term == currentTerm — we received AE from another leader
          // only one leader allowed per term in Raft
          timers.cancel("heartbeat")
          stepdown(node, term, leaderId)
          return transitionToFollowerAndReplay(
            node,
            AppendEntries(term, leaderId, prevLogIndex, prevLogTerm, entries, leaderCommit, replyTo)
          )
        }
      }

      def handleAppendEntriesResponse(
          timers: TimerScheduler[Command],
          term: Int,
          success: Boolean,
          sender: ActorRef[Command],
          matchIndexIfSuccess: Int
      ): Behavior[Command] = {

        if (term > node.currentTerm) {
          context.log.info(
            s"[${node.id}] <Leader> AppendEntriesResponse term $term > currentTerm ${node.currentTerm}, stepping down"
          )
          timers.cancel("heartbeat")
          stepdown(node, term)
          return node.follower()
        }

        if (term == node.currentTerm && success) {
          val follower = sender
          matchIndex = matchIndex.updated(follower.path.name, matchIndexIfSuccess)
          nextIndex = nextIndex.updated(follower.path.name, matchIndexIfSuccess + 1)
          context.log.debug(s"[${node.id}] <Leader> Updated nextIndex and matchIndex for follower")

          tryAdvanceCommitIndex()

          if (pendingReads.nonEmpty) {
            context.log.debug(
              s"[${node.id}] <Leader> Received AppendEntriesResponse from $follower, updating read quorum acks"
            )
            readQuorumAcks += sender
            val quorum = (node.peers.size + 1) / 2 + 1
            if (readQuorumAcks.size + 1 >= quorum) { // +1 for self
              context.log.info(
                s"[${node.id}] <Leader> Quorum for read confirmed. Serving pending reads."
              )
              respondToBufferedReads()

            }
          }
        }

        if (term == node.currentTerm && !success) {
          val follower     = sender
          val oldNextIndex = nextIndex.getOrElse(follower.path.name, node.log.size)
          val newNextIndex = math.max(1, oldNextIndex - 1)
          nextIndex = nextIndex.updated(follower.path.name, newNextIndex)

          context.log.info(
            s"[${node.id}] <Leader> AppendEntries failed, backing off nextIndex for ${follower.path.name}: $oldNextIndex → $newNextIndex"
          )
        }

        Behaviors.same
      }

      def tryAdvanceCommitIndex(
      ): Unit = {
        val matchIndexes  = matchIndex.values.toList :+ (node.log.size - 1) // include leader
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
        applyCommittedEntriesAndRespondClient()
      }

      def applyCommittedEntriesAndRespondClient(): Unit = {
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

          // If leader thinks it inserted something different at this index, notify that client of failure
          pendingClientAcks.get(node.lastApplied) match {
            case Some((cid, sn, ref)) if cid != entry.clientId || sn != entry.serialNum =>
              ref ! ClientResponse(
                success = false,
                message = "Request was lost due to leadership change"
              )
            case _                                                                      => // No mismatch or no extra client to notify
          }

          // Clean up
          pendingClientAcks -= node.lastApplied
        }
      }

      def respondToBufferedReads(
      ): Unit = {
        for ((key, clientId, serialNum, replyTo) <- pendingReads) {
          val value = node.stateMachine match {
            case fsm: FileAppendingStateMachine =>
              fsm.readKey(key)
            case _                              =>
              "Unsupported state machine"
          }
          replyTo ! ClientResponse(success = true, message = value)

          // clear state so we don’t reply again
          pendingReads = List.empty
          readQuorumAcks = Set.empty
        }
      }

      def handleRequestVote(
          timers: TimerScheduler[Command],
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
          timers.cancel("heartbeat")
          // votedfor = None if term > currentTerm
          stepdown(node, term) // unknown leader
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

      def handleUnexpectedVoteResponse(
          timers: TimerScheduler[Command],
          term: Int
      ): Behavior[Command] = {
        if (term > node.currentTerm) {
          context.log.info(
            s"[${node.id}] Stepping down: received unexpected VoteResponse with newer term $term"
          )
          timers.cancel("heartbeat")
          // votedfor = None before transit to follower
          stepdown(node, term) // No known leader in this case
          return node.follower()
        }
        Behaviors.same
      }

      Behaviors.receiveMessage {
        case WriteRequest(command, clientId, serialNum, replyTo) =>
          handleWriteRequest(command, clientId, serialNum, replyTo)

        case ReadRequest(key, clientId, serialNum, replyTo) =>
          handleReadRequest(key, clientId, serialNum, replyTo)
          Behaviors.same

        case SendHeartbeat =>
          sendAppendEntries()
          Behaviors.same

        case RequestVote(term, candidateId, lastLogIndex, lastLogTerm, replyTo) =>
          handleRequestVote(
            timers,
            term,
            candidateId,
            lastLogIndex,
            lastLogTerm,
            replyTo
          )

        case VoteResponse(term, _) =>
          handleUnexpectedVoteResponse(timers, term)

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
            timers,
            term,
            leaderId,
            prevLogIndex,
            prevLogTerm,
            entries,
            leaderCommit,
            replyTo
          )

        case AppendEntriesResponse(term, success, sender, matchIndexIfSuccess) =>
          handleAppendEntriesResponse(
            timers,
            term,
            success,
            sender,
            matchIndexIfSuccess
          )

        case SetPeers(p, partition) =>
          node.peers = p.filterNot(_ == context.self)
          node.partition =
            if (partition.nonEmpty) partition.filterNot(_ == context.self)
            else p.filterNot(_ == context.self)
          Behaviors.same

        // for testing
        case GetState(replyTo)      =>
          replyTo ! RaftState("Leader", node.id, node.currentTerm, node.log)
          Behaviors.same

        case msg =>
          context.log.debug(s"[${node.id}] <Leader> Received unknown or unhandled message: $msg")
          Behaviors.same
      }
    }
  }
}

```


#### Short summary: 

empty definition using pc, found symbol in pc: `<none>`.