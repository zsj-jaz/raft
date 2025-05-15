package raft

import akka.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.BeforeAndAfterAll
import raft.RaftNode._

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

class ClientRedirectSpec extends AnyWordSpecLike with BeforeAndAfterAll {
  val testKit = ActorTestKit()

  override def afterAll(): Unit = testKit.shutdownTestKit()

  "A Follower" should {
    "redirect client request to known leader" in {
      val clientProbe    = testKit.createTestProbe[ClientResponse]()
      val heartbeatProbe = testKit.createTestProbe[AppendEntriesResponse]()

      val state = new PersistentState("f3")
      state.currentTerm = 3
      state.votedFor = None
      state.log = List(
        LogEntry(0, "<dummy>", "", -1, None),
        LogEntry(3, "cmd", "clientX", 1, None)
      )

      val follower = testKit.spawn(RaftNode.applyWithState("f3", state))

      // Initial heartbeat to establish leader
      follower ! AppendEntries(
        term = 3,
        leaderId = "leader123",
        prevLogIndex = 1,
        prevLogTerm = 3,
        entries = Nil,
        leaderCommit = 1,
        replyTo = heartbeatProbe.ref
      )

      // Repeated heartbeats to prevent timeout
      val scheduler     = testKit.system.scheduler
      val heartbeatTask = scheduler.scheduleAtFixedRate(100.millis, 100.millis) { () =>
        follower ! AppendEntries(
          term = 3,
          leaderId = "leader123",
          prevLogIndex = 1,
          prevLogTerm = 3,
          entries = Nil,
          leaderCommit = 1,
          replyTo = heartbeatProbe.ref
        )
      }

      try {
        // Client request to follower
        follower ! ClientRequest("write something", "cli1", 1, clientProbe.ref)

        val response = clientProbe.expectMessageType[ClientResponse](500.millis)
        assert(!response.success)
        assert(response.message.contains("leader123"))
      } finally {
        heartbeatTask.cancel()
      }
    }
  }
}
