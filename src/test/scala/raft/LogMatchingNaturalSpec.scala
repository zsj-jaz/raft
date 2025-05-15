package raft

import akka.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike
import raft.RaftNode._
import scala.concurrent.duration._

class LogMatchingNaturalSpec extends AnyWordSpecLike {

  val config = ConfigFactory.parseString("""
    akka.loglevel = "INFO"
    akka.stdout-loglevel = "INFO"
  """)

  val testKit = ActorTestKit(config)

  "Raft cluster" should {
    "maintain log matching property under realistic client load" in {
      val numNodes = 5
      val nodes    = (1 to numNodes).map { i =>
        val state = new PersistentState(s"n$i")
        testKit.spawn(RaftNode.applyWithState(s"n$i", state), s"n$i")
      }

      // Set peers
      nodes.foreach { self =>
        val peers = nodes.filterNot(_ == self)
        self ! SetPeers(peers)
      }

      // Start long-lived clients
      val clients = (1 to 2).map { i =>
        testKit.spawn[ClientResponse](LongLivedClient(s"k$i", nodes), s"client$i")
      }

      val probe = testKit.createTestProbe[RaftState]()

      // Let system run and check log matching every second
      val checkDurationSeconds = 10
      for (t <- 1 to checkDurationSeconds) {
        println(s"\n[LOG MATCHING CHECK] Tick $t")
        Thread.sleep(1000)

        // Collect logs
        nodes.foreach(_ ! GetState(probe.ref))
        val stateMap =
          (1 to numNodes).map(_ => probe.receiveMessage(1.second)).map(s => s.id -> s.log).toMap

        // Check log matching property
        for {
          (id1, log1) <- stateMap
          (id2, log2) <- stateMap
          if id1 < id2 // avoid redundant checks
          i <- log1.indices if i < log2.length
          if log1(i).term == log2(i).term
        } {
          val prefix1 = log1.take(i + 1)
          val prefix2 = log2.take(i + 1)
          assert(
            prefix1 == prefix2,
            s"[VIOLATION] Logs differ at index $i for ($id1, $id2):\n$prefix1\n≠\n$prefix2"
          )
        }

        println(s"Log matching holds at tick $t")
      }

      // Cleanup
      clients.foreach(ref => testKit.stop(ref))
    }
  }
}
