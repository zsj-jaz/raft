package raft

import akka.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.BeforeAndAfterAll
import raft.RaftNode._
import scala.jdk.CollectionConverters._

import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets

class ApplyOnceSpec extends AnyWordSpecLike with BeforeAndAfterAll {
  val testKit                   = ActorTestKit()
  override def afterAll(): Unit = testKit.shutdownTestKit()

  "A Follower" should {
    "apply a committed entry only once even after multiple AppendEntries with same leaderCommit" in {
      val filePath = "state_machine_output_dedup_twice.txt"
      Files.deleteIfExists(Paths.get(filePath)) // clear previous runs

      val responseProbe = testKit.createTestProbe[AppendEntriesResponse]()

      val state = new PersistentState("dedup_twice")
      state.currentTerm = 3
      state.votedFor = None
      state.log = List(
        LogEntry(0, "<dummy>", "", -1, None),
        LogEntry(2, "wrong comman", "client2", 1, None)
      )

      val follower = testKit.spawn(RaftNode.applyWithState("dedup_twice", state))

      // Send the same AppendEntries ten times
      for (_ <- 1 to 10) {
        val entry = LogEntry(3, "SET Y", "client2", 1, None)
        follower ! AppendEntries(
          term = 3,
          leaderId = "leader3",
          prevLogIndex = 0,
          prevLogTerm = 0,
          entries = List(entry), // <- SEND ENTRY
          leaderCommit = 1,
          replyTo = responseProbe.ref
        )
        responseProbe.expectMessageType[AppendEntriesResponse]
      }

      //   Thread.sleep(500) // Give time to process

      // Read lines from state machine output
      val lines        = Files.readAllLines(Paths.get(filePath), StandardCharsets.UTF_8).asScala
      val appliedLines = lines.filter(_.contains("SET Y"))

      assert(
        appliedLines.size == 1,
        s"Expected only one application of SET Y, but got:\n${lines.toArray.mkString("\n")}"
      )
    }
  }
}
