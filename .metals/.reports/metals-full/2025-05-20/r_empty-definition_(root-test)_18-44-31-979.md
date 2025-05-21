error id: file://<WORKSPACE>/src/test/scala/raft/LogMatchingWithChurnSpec.scala:`<none>`.
file://<WORKSPACE>/src/test/scala/raft/LogMatchingWithChurnSpec.scala
empty definition using pc, found symbol in pc: `<none>`.
empty definition using semanticdb
empty definition using fallback
non-local guesses:

offset: 947
uri: file://<WORKSPACE>/src/test/scala/raft/LogMatchingWithChurnSpec.scala
text:
```scala
package raft

import akka.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike
import raft.RaftNode._
import scala.concurrent.duration._

class LogMatchingWithChurnSpec extends AnyWordSpecLike {

  val config = ConfigFactory.parseString("""
    akka.loglevel = "INFO"
    akka.stdout-loglevel = "INFO"
  """)

  val testKit = ActorTestKit(config)

  "Raft cluster with churn" should {
    "maintain the log matching property even with random leader crashes and restarts" in {
      val numNodes     = 5
      val initialNodes = (1 to numNodes).map { i =>
        val state = new PersistentState(s"n$i")
        testKit.spawn(RaftNode.applyWithState(s"n$i", state), s"n$i")
      }

      // Mutable list of currently alive nodes
      var nodes      = initialNodes.toList
      val allNodeIds = initialNodes.map(_.path.name).toSet

      // Set peers once at@@ the beginning and do not change afterward
      initialNodes.foreach { self =>
        val peers = initialNodes.filterNot(_ == self)
        self ! SetPeers(peers)
      }

      val clients = (1 to 2).map { i =>
        testKit.spawn[ClientResponse](LongLivedClient(s"k$i", initialNodes), s"client$i")
      }

      val probe                                        = testKit.createTestProbe[RaftState]()
      val totalTicks                                   = 10
      var crashed: Map[String, (Int, PersistentState)] = Map.empty

      def stripReplyTo(entry: LogEntry): (Int, String, String, Int) =
        (entry.term, entry.command, entry.clientId, entry.serialNum)

      for (tick <- 1 to totalTicks) {
        println(s"\n[Tick $tick]")

        // === Crash a leader every 2 ticks ===
        if (tick % 2 == 0) {
          nodes.foreach(_ ! GetState(probe.ref))
          val states    = nodes.map(_ => probe.receiveMessage(1.second))
          val leaderOpt = states.find(_.role == "Leader")

          leaderOpt.foreach { leaderState =>
            val leaderNodeOpt = nodes.find(_.path.name == leaderState.id)
            leaderNodeOpt.foreach { leader =>
              println(s"[TEST] Crashing leader ${leader.path.name}")
              testKit.stop(leader)
              val state = new PersistentState(leader.path.name).load()
              crashed += leader.path.name -> (tick, state)
              nodes = nodes.filterNot(_ == leader)
            }
          }
        }

        // === Restart crashed node after 4 ticks ===
        crashed.foreach { case (id, (crashTick, state)) =>
          if (tick == crashTick + 4 && !nodes.exists(_.path.name == id)) {
            println(s"[TEST] Restarting crashed node $id")
            val revived = testKit.spawn(RaftNode.applyWithState(id, state), id)
            nodes = nodes :+ revived
            revived ! SetPeers(nodes.filterNot(_.path.name == id))
            // but when revise should the peers be sent to the revived node??
            // Peers are already fixed at start; do not resend SetPeers
          }
        }

        // === Log matching check ===
        nodes.foreach(_ ! GetState(probe.ref))
        val stateMap = nodes.map { node =>
          val state = probe.receiveMessage(1.second)
          state.id -> state.log
        }.toMap

        for {
          (id1, log1) <- stateMap
          (id2, log2) <- stateMap
          if id1 < id2
          i           <- log1.indices if i < log2.length
          if log1(i).term == log2(i).term
        } {
          val prefix1 = log1.take(i + 1).map(stripReplyTo)
          val prefix2 = log2.take(i + 1).map(stripReplyTo)
          assert(
            prefix1 == prefix2,
            s"[VIOLATION] Logs differ at index $i for ($id1, $id2):\n$prefix1\n≠\n$prefix2"
          )
        }

        println(s"Log matching holds at tick $tick")
        Thread.sleep(1000)
      }

      // Cleanup clients
      clients.foreach(ref => testKit.stop(ref))
    }
  }
}

```


#### Short summary: 

empty definition using pc, found symbol in pc: `<none>`.