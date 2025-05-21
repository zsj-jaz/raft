error id: file://<WORKSPACE>/src/main/scala/raft/RaftNode.scala:`<none>`.
file://<WORKSPACE>/src/main/scala/raft/RaftNode.scala
empty definition using pc, found symbol in pc: `<none>`.
empty definition using semanticdb
empty definition using fallback
non-local guesses:
	 -scala/concurrent/duration/UnstableRead#
	 -UnstableRead#
	 -scala/Predef.UnstableRead#
offset: 1521
uri: file://<WORKSPACE>/src/main/scala/raft/RaftNode.scala
text:
```scala
package raft

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors

import scala.concurrent.duration._
import scala.util.Random

object RaftNode {

  // === Commands / Messages ===
  sealed trait Command

  // RPCs
  final case class RequestVote(
      term: Int,
      candidateId: String,
      lastLogIndex: Int,
      lastLogTerm: Int,
      replyTo: ActorRef[VoteResponse]
  ) extends Command

  final case class VoteResponse(term: Int, voteGranted: Boolean) extends Command

  final case class AppendEntries(
      term: Int,
      leaderId: String,
      prevLogIndex: Int,
      prevLogTerm: Int,
      entries: List[LogEntry],
      leaderCommit: Int,
      replyTo: ActorRef[AppendEntriesResponse]
  ) extends Command

  final case class AppendEntriesResponse(
      term: Int,
      success: Boolean,
      sender: ActorRef[Command],
      matchIndexIfSuccess: Int // so I know how to update matchIndex
  ) extends Command

  final case class WriteRequest(
      command: String,
      clientId: String,
      serialNum: Int,
      replyTo: ActorRef[WriteResponse]
  ) extends Command

  final case class ReadRequest(
      key: String,
      clientId: String,
      serialNum: Int,
      replyTo: ActorRef[ReadResponse]
  ) extends Command

  sealed trait ClientResponse

  final case class WriteResponse(success: Boolean, message: String) extends ClientResponse
  final case class ReadResponse(value: String)                      extends ClientResponse

  final case class @@UnstableRead(
      key: String,
      replyTo: ActorRef[ReadResponse]
  ) extends Command

  // Internal control
  case object SendHeartbeat   extends Command
  case object ElectionTimeout extends Command

  final case class SetPeers(
      peers: Seq[ActorRef[Command]],
      partition: Seq[ActorRef[Command]] = Seq.empty // optional
  ) extends Command
  final case class GetState(replyTo: ActorRef[RaftState]) extends Command
  case object BecomeLeader                                extends Command

  // Log entry
  final case class LogEntry(
      term: Int,
      command: String,
      clientId: String,
      serialNum: Int,
      replyTo: Option[ActorRef[WriteResponse]] // leave empty for no-op
  )

  // For testing/debugging
  final case class RaftState(role: String, id: String, term: Int, log: List[LogEntry])

  def createForTest(id: String, presetState: PersistentState): RaftNode = {
    new RaftNode(id, presetState)
  }

  // Start the Raft node as follower
  def apply(id: String): Behavior[Command] = {
    Behaviors.setup { context =>
      val defaultState = new PersistentState(id).load()
      applyWithState(id, defaultState)
    }
  }

  // === Test ===
  def applyWithState(id: String, presetState: PersistentState): Behavior[Command] = {
    Behaviors.setup { context =>
      val node = new RaftNode(id, presetState)
      node.follower()
    }
  }

}

class RaftNode private (val id: String, val state: PersistentState) {
  import RaftNode._

  // === Persistent state on all servers ===
  require(state.log.nonEmpty, "Log must not be empty")
  require(
    state.log.head.term == 0 && state.log.head.command == "<dummy>",
    "Log must start with dummy entry at index 0"
  )

  val stateMachine: StateMachine = new FileAppendingStateMachine(id)

  def currentTerm: Int         = state.currentTerm
  def votedFor: Option[String] = state.votedFor
  def log: List[LogEntry]      = state.log

  def setCurrentTerm(term: Int): Unit = {
    state.currentTerm = term
    state.persist()
  }

  def setVotedFor(id: Option[String]): Unit = {
    state.votedFor = id
    state.persist()
  }

  // === Volatile state on all servers ===
  var commitIndex: Int = 0 // nothing committed yet
  var lastApplied: Int = 0 // dummy is applied or ignored

  // // === Volatile state on leaders (reinitialized after election) ===
  // var matchIndex: Map[ActorRef[Command], Int] = Map.empty
  // var nextIndex: Map[ActorRef[Command], Int]  = Map.empty

  // === Other shared state ===
  var peers: Seq[ActorRef[Command]]     = Seq.empty
  var partition: Seq[ActorRef[Command]] = Seq.empty
  var leaderId: Option[String]          = None

  // === Role transitions ===
  def follower(): Behavior[Command]                        = FollowerBehavior(this)
  def candidate(votesReceived: Int = 1): Behavior[Command] = CandidateBehavior(this, votesReceived)
  def leader(): Behavior[Command]                          = LeaderBehavior(this)

  def setLeaderId(id: Option[String]): Unit = {
    leaderId = id
  }

  // Randomized election timeout: 150–300ms
  def randomElectionTimeout(): FiniteDuration = {
    (150 + Random.nextInt(150)).millis
  }
}

```


#### Short summary: 

empty definition using pc, found symbol in pc: `<none>`.