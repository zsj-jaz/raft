package raft

import akka.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.BeforeAndAfterAll
import raft.RaftNode._
import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters._

class ReappearingIndexSpec extends AnyWordSpecLike with BeforeAndAfterAll {
  val testKit                   = ActorTestKit()
  override def afterAll(): Unit = testKit.shutdownTestKit()

  "Reappearing index" should {
    "overwrite older log entry and apply new one" in {
      val filePath = "state_machine_output_s1_reappear.txt"
      Files.deleteIfExists(Paths.get(filePath)) // clean slate

      val state = new PersistentState("s1_reappear")
      state.currentTerm = 3
      state.votedFor = Some("s1")
      state.log = List(
        LogEntry(0, "<dummy>", "", -1, None),
        LogEntry(2, "SET x=1", "cli1", 1, None), // C1
        LogEntry(3, "SET a=4", "cli4", 4, None)  // C4 (will be overwritten)
      )

      val node = RaftNode.createForTest("s1_reappear", state)
      val s1   = testKit.spawn(RaftNode.applyWithState("s1_reappear", state), "s1-reappear")

      val responseProbe = testKit.createTestProbe[AppendEntriesResponse]()

      // Leader S2 sends [C1, C2, C5] with commitIndex=3
      val newLog = List(
        LogEntry(1, "SET x=1", "cli1", 1, None),
        LogEntry(1, "SET y=2", "cli2", 2, None),
        LogEntry(4, "SET u=5", "cli5", 5, None)
      )

      s1 ! AppendEntries(
        term = 4,
        leaderId = "s2",
        prevLogIndex = 0,
        prevLogTerm = 0,
        entries = newLog,
        leaderCommit = 3,
        replyTo = responseProbe.ref
      )

      responseProbe.expectMessageType[AppendEntriesResponse]

      val lines   = Files.readAllLines(Paths.get(filePath), StandardCharsets.UTF_8).asScala
      val applied = lines.mkString("\n")

      assert(applied.contains("SET x=1"), "Expected SET x=1 to be applied")
      assert(applied.contains("SET y=2"), "Expected SET y=2 to overwrite C4 and be applied")
      assert(applied.contains("SET u=5"), "Expected SET u=5 to be applied")
      assert(!applied.contains("SET a=4"), "SET a=4 (C4) should NOT be applied")
    }
  }
}
