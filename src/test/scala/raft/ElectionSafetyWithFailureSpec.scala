package raft

import akka.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike
import raft.RaftNode._
import scala.concurrent.duration._

class ElectionSafetyWithFailureSpec extends AnyWordSpecLike {

  val config = ConfigFactory.parseString("""
    akka.loglevel = "INFO"
    akka.stdout-loglevel = "INFO"
  """)

  val testKit = ActorTestKit(config)

  "Raft cluster" should {
    "elect at most one leader per term, even after leader failure" in {
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

      val probe = testKit.createTestProbe[RaftState]()

      // Wait for election
      Thread.sleep(2000)

      // === Round 1: Collect States ===
      nodes.foreach(_ ! GetState(probe.ref))
      val stateRound1 = (1 to nodes.size).map(_ => probe.receiveMessage(1.second))

      val stateMapRound1 = stateRound1.map(s => s.id -> s).toMap
      val leaderOpt1     = stateRound1.find(_.role == "Leader")
      assert(leaderOpt1.isDefined, "Expected one leader in round 1")
      val leaderState1   = leaderOpt1.get
      val leaderNode1    = nodes.find(_.path.name == leaderState1.id).get

      println(s"[ROUND 1] Leader is ${leaderState1.id} in term ${leaderState1.term}")

      // === Kill the leader ===
      testKit.stop(leaderNode1)

      // Wait for re-election
      Thread.sleep(3000)

      val remaining   = nodes.filterNot(_ == leaderNode1)
      remaining.foreach(_ ! GetState(probe.ref))
      val stateRound2 = (1 to remaining.size).map(_ => probe.receiveMessage(1.second))

      val stateMapRound2 = stateRound2.map(s => s.id -> s).toMap
      val leaderOpt2     = stateRound2.find(_.role == "Leader")
      assert(leaderOpt2.isDefined, "Expected one leader in round 2")
      val leaderState2   = leaderOpt2.get
      val leaderNode2    = remaining.find(_.path.name == leaderState2.id).get

      println(s"[ROUND 2] Leader is ${leaderState2.id} in term ${leaderState2.term}")

      // === Safety Check: At Most One Leader per Term ===
      val allStates     = stateRound1 ++ stateRound2
      val groupedByTerm = allStates.groupBy(_.term)

      groupedByTerm.foreach { case (term, states) =>
        val leadersInTerm = states.count(_.role == "Leader")
        assert(leadersInTerm <= 1, s"[SAFETY VIOLATION] More than one leader elected in term $term")
      }

      assert(
        leaderState2.term > leaderState1.term,
        "Expected a new term after leader failure"
      )
    }
  }
}
