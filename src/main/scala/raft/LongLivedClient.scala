package raft

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{Behaviors, ActorContext, TimerScheduler}
import scala.concurrent.duration._
import raft.RaftNode.{ClientRequest, ClientResponse, Command}

object LongLivedClient {

  def apply(command: String, nodes: Seq[ActorRef[Command]]): Behavior[ClientResponse] = {
    Behaviors.withTimers { timers =>
      Behaviors.setup { context =>
        val clientId  = context.self.path.name
        var serialNum = 1

        def send(nodeOpt: Option[ActorRef[Command]] = None): Unit = {
          val target = nodeOpt.getOrElse(pickRandomNode(nodes))
          context.log.info(
            s"[LongLived-$clientId] Sending $command ($serialNum) to ${target.path.name}"
          )
          target ! ClientRequest(command, clientId, serialNum, context.self)
          timers.startSingleTimer("timeout", ClientResponse(false, "timeout"), 2.seconds)
        }

        def handleClientResponse(success: Boolean, msg: String): Unit = {
          if (success) {
            context.log.info(s"[LongLived-$clientId] Success: $msg")
            serialNum += 1
            // Optional delay between requests (for debugging visibility)
            timers.startSingleTimer("tick", ClientResponse(false, "tick"), 3.seconds)
          } else if (msg == "tick") {
            send()
          } else if (msg == "timeout") {
            context.log.info(s"[LongLived-$clientId] Timeout — retrying.")
            send()
          } else {
            context.log.info(s"[LongLived-$clientId] Redirected: $msg")
            val leaderRedirection = extractRedirection(msg, nodes).getOrElse(pickRandomNode(nodes))
            context.log.info(s"[LongLived-$clientId] Retrying with ${leaderRedirection.path.name}")
            send(Some(leaderRedirection))
          }
        }

        def pickRandomNode(nodes: Seq[ActorRef[Command]]): ActorRef[Command] = {
          val rnd = scala.util.Random
          nodes(rnd.nextInt(nodes.length))
        }

        def extractRedirection(
            msg: String,
            nodes: Seq[ActorRef[Command]]
        ): Option[ActorRef[Command]] = {
          val maybeId = msg.stripPrefix("Redirect to leader ").trim
          nodes.find(n => n.path.name == maybeId)
        }

        send()

        Behaviors.receiveMessage { case ClientResponse(success, msg) =>
          handleClientResponse(success, msg)
          Behaviors.same
        }
      }
    }
  }
}
