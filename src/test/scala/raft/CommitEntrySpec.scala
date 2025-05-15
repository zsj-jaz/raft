package raft

import akka.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.BeforeAndAfterAll
import raft.RaftNode._

class CommitEntrySpec extends AnyWordSpecLike with BeforeAndAfterAll {
  val testKit                   = ActorTestKit()
  override def afterAll(): Unit = testKit.shutdownTestKit()

  "A Leader" should {
    "advance commitIndex and apply entry after quorum Ack" in {
      val peer1  = testKit.createTestProbe[Command]()
      val peer2  = testKit.createTestProbe[Command]()
      val client = testKit.createTestProbe[ClientResponse]()

      val state = new PersistentState("commit_test")
      state.currentTerm = 3
      state.votedFor = Some("leader1")

      // Entry at index 1, not committed yet
      val entry = LogEntry(3, "SET X", "clientA", 42, Some(client.ref))
      state.log = List(LogEntry(0, "<dummy>", "", -1, None), entry)

      val node = RaftNode.createForTest("leader1", state)
      node.peers = Seq(peer1.ref, peer2.ref)

      val leader = testKit.spawn(raft.LeaderBehavior(node))

      // Simulate both peers returning success with matchIndex = 1
      leader ! AppendEntriesResponse(
        term = 3,
        success = true,
        sender = peer1.ref,
        matchIndexIfSuccess = 1
      )
      leader ! AppendEntriesResponse(
        term = 3,
        success = true,
        sender = peer2.ref,
        matchIndexIfSuccess = 1
      )

      // Leader should apply the entry and respond to client
      val reply = client.receiveMessage()

      assert(reply.success, "Client should receive success")
      assert(reply.message.contains("SET X"), s"Unexpected reply: ${reply.message}")
      assert(node.commitIndex == 1, s"Expected commitIndex=1, got ${node.commitIndex}")
    }
  }
}
