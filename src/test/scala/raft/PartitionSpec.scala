package raft

import akka.actor.testkit.typed.scaladsl.{ActorTestKit, LoggingTestKit, TestProbe}
import org.scalatest.wordspec.AnyWordSpecLike
import raft.RaftNode._
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

class PartitionSpec extends AnyWordSpecLike {

  val config = ConfigFactory.parseString("""
    akka.loglevel = "INFO"
    akka.stdout-loglevel = "INFO"
  """)

  val testKit = ActorTestKit(config)

  "Raft cluster" should {
    "elect leader in majority partition and not in minority" in {
      val nodes = (1 to 5).map { i =>
        val state = new PersistentState(s"n$i")
        val raft  = testKit.spawn(RaftNode.applyWithState(s"n$i", state), s"n$i")
        raft
      }

      val Seq(n1, n2, n3, n4, n5) = nodes
      val allPeers                = nodes

      val majorityPartition = Seq(n3, n4, n5)
      val minorityPartition = Seq(n1, n2)

      // Apply partitioned peers setup
      allPeers.foreach { n =>
        val peers     = allPeers.filterNot(_ == n)
        val partition =
          if (majorityPartition.contains(n)) majorityPartition.filterNot(_ == n)
          else if (minorityPartition.contains(n)) minorityPartition.filterNot(_ == n)
          else Seq.empty
        n ! SetPeers(peers, partition)
      }

      // Wait for elections
      Thread.sleep(3000)

      val probe = testKit.createTestProbe[RaftState]()

      // Send GetState to all nodes
      allPeers.foreach(_ ! GetState(probe.ref))

      val leaderStates = probe
        .receiveMessages(5, 2.seconds)
        .filter(_.role == "Leader")

      assert(leaderStates.size == 1, s"Expected 1 leader, but found ${leaderStates.size}")
      assert(
        majorityPartition.exists(n => leaderStates.exists(_.id == n.path.name)),
        "Leader must be in majority partition (n3, n4, n5)"
      )
    }
  }
}
