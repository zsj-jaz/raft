// File: src/test/scala/raft/RaftVoteSpec.scala
package raft

import akka.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import akka.actor.typed.ActorRef
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.BeforeAndAfterAll
import raft.RaftNode._

class RaftVoteSpec extends AnyWordSpecLike with BeforeAndAfterAll {
  val testKit = ActorTestKit()

  override def afterAll(): Unit = testKit.shutdownTestKit()

  "A Follower" should {
    "deny vote if candidate log is stale" in {
      val voteProbe = testKit.createTestProbe[VoteResponse]()

      // --- Custom persistent state for follower node ---
      val state = new PersistentState("f1")
      state.currentTerm = 2
      state.votedFor = None
      state.log = List(
        LogEntry(0, "<dummy>", "", -1, None),    // index 0
        LogEntry(2, "set x", "client1", 1, None) // index 1, term 2
      )

      val follower = testKit.spawn(RaftNode.applyWithState("f1", state))

      // --- Candidate log is stale (term 1 < follower’s last log term 2) ---
      follower ! RequestVote(
        term = 2,
        candidateId = "cand",
        lastLogIndex = 1,
        lastLogTerm = 1,
        replyTo = voteProbe.ref
      )

      val response = voteProbe.expectMessageType[VoteResponse]
      assert(!response.voteGranted)
    }
  }

  "A Follower" should {
    "grant vote if candidate log is up-to-date and no prior vote" in {
      val voteProbe = testKit.createTestProbe[VoteResponse]()

      // Follower's current state
      val state = new PersistentState("f2")
      state.currentTerm = 2
      state.votedFor = None
      state.log = List(
        LogEntry(0, "<dummy>", "", -1, None),    // index 0
        LogEntry(2, "set x", "client1", 1, None) // index 1, term 2
      )

      val follower = testKit.spawn(RaftNode.applyWithState("f2", state))

      // Candidate's log is same length, same term (up-to-date)
      follower ! RequestVote(
        term = 2,
        candidateId = "cand2",
        lastLogIndex = 1,
        lastLogTerm = 2,
        replyTo = voteProbe.ref
      )

      val response = voteProbe.expectMessageType[VoteResponse]
      assert(response.voteGranted)
    }
  }

}
