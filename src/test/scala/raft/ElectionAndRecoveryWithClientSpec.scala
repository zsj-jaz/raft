package raft

import akka.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike
import raft.RaftNode._
import scala.concurrent.duration._

class ElectionAndRecoveryWithClientSpec extends AnyWordSpecLike {

  val config  = ConfigFactory.parseString("""
    akka.loglevel = "INFO"
    akka.stdout-loglevel = "INFO"
  """)
  val testKit = ActorTestKit(config)

  "Raft cluster with client interaction and leader recovery" should {
    "elect leader, process client request, reelect after failure, and rejoin with state recovery" in {
      val numNodes = 5
      val nodes    = (1 to numNodes).map { i =>
        val state = new PersistentState(s"n$i")
        testKit.spawn(RaftNode.applyWithState(s"n$i", state), s"n$i")
      }

      // Connect peers
      nodes.foreach { self =>
        val peers = nodes.filterNot(_ == self)
        self ! SetPeers(peers, peers)
      }

      val probe  = testKit.createTestProbe[RaftState]()
      val client = testKit.createTestProbe[ClientResponse]()

      // === Wait for first leader election ===
      Thread.sleep(2000)
      nodes.foreach(_ ! GetState(probe.ref))
      val stateRound1 = (1 to nodes.size).map(_ => probe.receiveMessage(1.second))

      val stateMapRound1 = stateRound1.map(s => s.id -> s).toMap
      val leaderOpt      =
        stateRound1.find(_.role == "Leader").flatMap(s => nodes.find(_.path.name == s.id))

      assert(leaderOpt.isDefined, "Expected one leader in round 1")
      val leader = leaderOpt.get
      println(
        s"[ROUND 1] Leader is ${leader.path.name} in term ${stateMapRound1(leader.path.name).term}"
      )

      // === Send client request to initial leader ===
      println(s"[CLIENT] Sending request 'SET x=42'")
      leader ! ClientRequest("SET x=42", "clientA", 1, client.ref)
      val response1 = client.receiveMessage(2.seconds)
      println(s"[CLIENT] Got response: $response1")

      // === Kill the leader ===
      println(s"[TEST] Stopping leader ${leader.path.name}")
      testKit.stop(leader)

      // === Wait for re-election ===
      Thread.sleep(3000)
      val remaining   = nodes.filterNot(_ == leader)
      remaining.foreach(_ ! GetState(probe.ref))
      val stateRound2 = (1 to remaining.size).map(_ => probe.receiveMessage(1.second))

      val stateMapRound2 = stateRound2.map(s => s.id -> s).toMap
      val newLeaderOpt   =
        stateRound2.find(_.role == "Leader").flatMap(s => remaining.find(_.path.name == s.id))

      assert(newLeaderOpt.isDefined, "Expected one leader after failure")
      val newLeader = newLeaderOpt.get
      println(
        s"[ROUND 2] New leader is ${newLeader.path.name} in term ${stateMapRound2(newLeader.path.name).term}"
      )

      // === Send another client request ===
      println(s"[CLIENT] Sending request 'SET y=9'")
      newLeader ! ClientRequest("SET y=9", "clientB", 2, client.ref)
      val response2 = client.receiveMessage(2.seconds)
      println(s"[CLIENT] Got response: $response2")

      // === Restart original leader from file ===
      println(s"[TEST] Restarting original leader from file: ${leader.path.name}")
      val revivedState = new PersistentState(leader.path.name).load()
      val revivedNode  =
        testKit.spawn(RaftNode.applyWithState(leader.path.name, revivedState), leader.path.name)

      // Reconnect all peers including revived node
      val allNodes = remaining :+ revivedNode
      allNodes.foreach { self =>
        val peers = allNodes.filterNot(_ == self)
        self ! SetPeers(peers)
      }

      // === Final state check ===
      Thread.sleep(2000)
      allNodes.foreach(_ ! GetState(probe.ref))
      val finalStates = (1 to allNodes.size).map(_ => probe.receiveMessage(1.second))
      val finalMap    = finalStates.map(s => s.id -> s).toMap

      finalMap.foreach { case (id, state) =>
        println(
          s"[FINAL STATE] $id: role=${state.role}, term=${state.term}, log=${state.log.map(_.command)}"
        )
      }
    }
  }
}
