package raft

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import scala.io.StdIn
import scala.concurrent.duration._

object Orchestrator {

  case object Shutdown

  def apply(numNodes: Int, numClients: Int): Behavior[Shutdown.type] = Behaviors.setup { context =>

    // Spawn Raft nodes
    val nodes: Seq[ActorRef[RaftNode.Command]] =
      (1 to numNodes).map { i =>
        val id = s"n$i"
        context.spawn(RaftNode(id), id)
      }

    // Set peers for each node
    nodes.foreach { node =>
      node ! RaftNode.SetPeers(nodes, nodes)
    }

    // Spawn clients
    (1 to numClients).foreach { i =>
      val clientId = s"client$i"
      context.spawn(LongLivedClient(baseKey = s"k$i-", nodes), clientId)
    }

    // Watch for Enter key to shut down
    Behaviors.receiveMessage { case Shutdown =>
      println("Shutting down...")
      Behaviors.stopped
    }
  }

  def main(args: Array[String]): Unit = {
    println("How many Raft nodes?")
    val numNodes = StdIn.readLine().trim.toInt

    println("How many clients?")
    val numClients = StdIn.readLine().trim.toInt

    val system = ActorSystem(Orchestrator(numNodes, numClients), "RaftCluster")

    println("Press ENTER to stop the system...")
    StdIn.readLine()
    system ! Shutdown
  }

}
