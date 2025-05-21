package raft

import akka.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.BeforeAndAfterAll
import raft.RaftNode._

class ReadRequestSpec extends AnyWordSpecLike with BeforeAndAfterAll {
  val testKit                   = ActorTestKit()
  override def afterAll(): Unit = testKit.shutdownTestKit()

  "A Leader" should {
    "respond with correct value for a linearizable ReadRequest" in {
      val peer1 = testKit.createTestProbe[Command]()
      val peer2 = testKit.createTestProbe[Command]()

      val state = new PersistentState("read_leader")
      state.currentTerm = 10
      state.votedFor = Some("leaderX")
      state.log = List(
        LogEntry(0, "<dummy>", "", -1, None),
        LogEntry(10, "SET x=1", "cli-set", 1, None)
      )

      val node = RaftNode.createForTest("leaderX", state)
      node.peers = Seq(peer1.ref, peer2.ref)

      val leader = testKit.spawn(raft.LeaderBehavior(node), "read-leader")

      // Wait briefly to allow initial heartbeat (AppendEntries) to be sent
      //   Thread.sleep(100)

      // Now send the ReadRequest
      val client = testKit.createTestProbe[ClientResponse]()
      leader ! ReadRequest("x", clientId = "cli-read", serialNum = 1, replyTo = client.ref)

      // Simulate followers replying to heartbeat to form quorum
      leader ! AppendEntriesResponse(
        term = 10,
        success = true,
        sender = peer1.ref,
        matchIndexIfSuccess = 1 // SET x=1 is at index 1
      )

      leader ! AppendEntriesResponse(
        term = 10,
        success = true,
        sender = peer2.ref,
        matchIndexIfSuccess = 1
      )

      // Expect the correct read result
      val response = client.expectMessageType[ReadResponse]
      assert(response.value == "x=1", s"Expected x=1, but got: ${response.value}")

    }
  }
}
