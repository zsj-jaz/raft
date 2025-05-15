package raft

import akka.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.scalatest.wordspec.AnyWordSpecLike
import raft.RaftNode._
import scala.concurrent.duration._

class LeaderAppendOnlySpec extends AnyWordSpecLike {
  val testKit = ActorTestKit()

  "A Raft Leader" should {
    "only append to its log over time" in {
      val state = new PersistentState("n1")
      state.log = List(
        LogEntry(0, "<dummy>", "", -1, None),
        LogEntry(1, "SET x=1", "clientA", 1, None)
      )
      state.currentTerm = 1
      state.votedFor = Some("n1")

      val node = testKit.spawn(RaftNode.applyWithState("n1", state), "n1")
      node ! SetPeers(Seq()) // no followers
      node ! ElectionTimeout // become leader

      Thread.sleep(500) // allow transition to leader

      val probe = testKit.createTestProbe[ClientResponse]()

      var previousSize = state.log.size

      val startTime = System.currentTimeMillis()
      val endTime   = startTime + 2000 // run for 2 seconds

      var commandIndex = 2

      while (System.currentTimeMillis() < endTime) {
        val cmd = s"SET key$commandIndex=value$commandIndex"
        node ! ClientRequest(cmd, "clientA", commandIndex, probe.ref)
        probe.expectMessageType[ClientResponse](1.second)

        val newSize = state.log.size
        assert(
          newSize >= previousSize,
          s"Log size shrunk! before=$previousSize, after=$newSize"
        )
        previousSize = newSize
        commandIndex += 1

        Thread.sleep(100) // slight delay between requests
      }
    }
  }
}
