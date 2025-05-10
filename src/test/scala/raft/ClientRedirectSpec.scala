package raft

import akka.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.BeforeAndAfterAll
import raft.RaftNode._

class ClientRedirectSpec extends AnyWordSpecLike with BeforeAndAfterAll {
  val testKit = ActorTestKit()

  override def afterAll(): Unit = testKit.shutdownTestKit()

  "A Follower" should {
    "redirect client request to known leader" in {
      val clientProbe = testKit.createTestProbe[ClientResponse]()

      val state = new PersistentState("f3")
      state.currentTerm = 3
      state.votedFor = None
      state.log = List(
        LogEntry(0, "<dummy>", "", -1, None),
        LogEntry(3, "cmd", "clientX", 1, None)
      )

      val follower = testKit.spawn(RaftNode.applyWithState("f3", state))

      // Simulate hearing from a leader
      follower ! AppendEntries(
        term = 3,
        leaderId = "leader123",
        prevLogIndex = 1,
        prevLogTerm = 3,
        entries = Nil,
        leaderCommit = 1,
        replyTo = testKit.createTestProbe().ref
      )

      Thread.sleep(200)

      // Client request to follower
      follower ! ClientRequest("write something", "cli1", 1, clientProbe.ref)

      val response = clientProbe.expectMessageType[ClientResponse]
      assert(!response.success)
      assert(response.message.contains("leader123"))
    }
  }
}
