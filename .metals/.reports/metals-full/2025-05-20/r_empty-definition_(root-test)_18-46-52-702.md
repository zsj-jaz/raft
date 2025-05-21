error id: file://<WORKSPACE>/src/test/scala/raft/SplitBrainSpec.scala:`<none>`.
file://<WORKSPACE>/src/test/scala/raft/SplitBrainSpec.scala
empty definition using pc, found symbol in pc: `<none>`.
empty definition using semanticdb
empty definition using fallback
non-local guesses:

offset: 1410
uri: file://<WORKSPACE>/src/test/scala/raft/SplitBrainSpec.scala
text:
```scala
package raft

import akka.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.scalatest.wordspec.AnyWordSpecLike
import raft.RaftNode._
import com.typesafe.config.ConfigFactory
import akka.actor.typed.ActorRef

import scala.concurrent.duration._

class SplitBrainSpec extends AnyWordSpecLike {

  val config = ConfigFactory.parseString("""
    akka.loglevel = "INFO"
    akka.stdout-loglevel = "INFO"
  """)

  val testKit = ActorTestKit(config)

  "Raft cluster" should {
    "recover after split-brain and commit after healing" in {
      val nodes = (1 to 5).map { i =>
        val state = new PersistentState(s"n$i")
        testKit.spawn(RaftNode.applyWithState(s"n$i", state), s"n$i")
      }

      val Seq(n1, n2, n3, n4, n5) = nodes
      val all                     = nodes

      val minority = Seq(n1, n2)
      val majority = Seq(n3, n4, n5)

      // Simulate partition
      minority.foreach(n => n ! SetPeers(all.filterNot(_ == n), minority.filterNot(_ == n)))
      majority.foreach(n => n ! SetPeers(all.filterNot(_ == n), majority.filterNot(_ == n)))

      // Let elections happen
      Thread.sleep(3000)

      // Request to majority (should succeed)
      val client1 = testKit.createTestProbe[WriteResponse]()
      n3 ! WriteRequest("SET k=1", "client", 1, client1.ref)
      client1.expectMessageType[ClientResponse](2.seconds)

      // Request to minority (should fail@@ or time out)
      val client2   = testKit.createTestProbe[WriteResponse]()
      n1 ! WriteRequest("SET stale=2", "client", 2, client2.ref)
      val response2 = client2.expectMessageType[ClientResponse](2.seconds)
      assert(!response2.success)
      assert(response2.message.contains("Leader unknown") || response2.message.contains("Redirect"))

      // Heal the partition
      all.foreach(n => n ! SetPeers(all.filterNot(_ == n))) // default: partition = peers
      println("[TEST] healed partition")

      Thread.sleep(3000) // Let cluster stabilize

      // Request after healing — should succeed
      val client3 = testKit.createTestProbe[ClientResponse]()
      n3 ! WriteRequest("SET k=2", "client", 2, client3.ref)
      client3.expectMessageType[ClientResponse](2.seconds)
    }
  }
}

```


#### Short summary: 

empty definition using pc, found symbol in pc: `<none>`.