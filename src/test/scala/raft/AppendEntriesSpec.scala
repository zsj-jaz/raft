package raft

import akka.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.BeforeAndAfterAll
import raft.RaftNode._

class AppendEntriesSpec extends AnyWordSpecLike with BeforeAndAfterAll {
  val testKit                   = ActorTestKit()
  override def afterAll(): Unit = testKit.shutdownTestKit()

  "A Follower" should {
    "reject AppendEntries with invalid prevLogIndex even if entries is empty (heartbeat)" in {
      val replyProbe = testKit.createTestProbe[AppendEntriesResponse]()

      // Set up a follower with a 1-entry log
      val state = new PersistentState("f_invalid_index")
      state.currentTerm = 5
      state.votedFor = None
      state.log = List(
        LogEntry(0, "<dummy>", "", -1, None),
        LogEntry(5, "set x", "cliX", 1, None)
      ) // log size = 2

      val follower = testKit.spawn(RaftNode.applyWithState("f_invalid_index", state))

      // Leader sends heartbeat with prevLogIndex too large
      follower ! AppendEntries(
        term = 5,
        leaderId = "leader5",
        prevLogIndex = 10, // invalid
        prevLogTerm = 5,
        entries = Nil,     // heartbeat
        leaderCommit = 1,
        replyTo = replyProbe.ref
      )

      val response = replyProbe.expectMessageType[AppendEntriesResponse]
      assert(!response.success, "Follower should reject AppendEntries with invalid prevLogIndex")
    }
  }
}
