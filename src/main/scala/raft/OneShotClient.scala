package raft

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{Behaviors, ActorContext, TimerScheduler}
import scala.concurrent.duration._
import raft.RaftNode.{ClientRequest, ClientResponse, Command}

object OneShotClient {

  def apply(command: String, nodes: Seq[ActorRef[Command]]): Behavior[ClientResponse] = {
    Behaviors.withTimers { timers =>
      Behaviors.setup { context =>
        val clientId  = context.self.path.name
        val serialNum = 1

        sendRequest(command, clientId, serialNum, nodes, context, timers)

        Behaviors.receiveMessage {
          case ClientResponse(false, msg) if msg == "timeout" =>
            context.log.info(s"[Client-$clientId] Request timed out — retrying")
            val fallback = pickRandomNode(nodes)
            sendRequest(command, clientId, serialNum, nodes, context, timers, Some(fallback))
            Behaviors.same

          case ClientResponse(false, msg) =>
            context.log.info(s"[Client-$clientId] Not leader — message: '$msg'")
            val leaderOpt = extractRedirection(msg, nodes)
            val fallback  = leaderOpt.getOrElse(pickRandomNode(nodes))
            context.log.info(s"[Client-$clientId] Retrying with ${fallback.path.name}")
            sendRequest(command, clientId, serialNum, nodes, context, timers, Some(fallback))
            Behaviors.same

          case ClientResponse(true, msg) =>
            context.log.info(s"[Client-$clientId] Success: $msg")
            Behaviors.stopped
        }
      }
    }
  }

  private def sendRequest(
      command: String,
      clientId: String,
      serialNum: Int,
      nodes: Seq[ActorRef[Command]],
      context: ActorContext[ClientResponse],
      timers: TimerScheduler[ClientResponse],
      nodeOpt: Option[ActorRef[Command]] = None
  ): Unit = {
    val target = nodeOpt.getOrElse(pickRandomNode(nodes))
    context.log.info(s"[Client-$clientId] Sending to ${target.path.name} (serial $serialNum)")
    target ! ClientRequest(command, clientId, serialNum, context.self)
    timers.startSingleTimer("timeout", ClientResponse(false, "timeout"), 2.seconds)
  }

  private def pickRandomNode(nodes: Seq[ActorRef[Command]]): ActorRef[Command] = {
    val rnd = scala.util.Random
    nodes(rnd.nextInt(nodes.length))
  }

  private def extractRedirection(
      msg: String,
      nodes: Seq[ActorRef[Command]]
  ): Option[ActorRef[Command]] = {
    val maybeId = msg.stripPrefix("Redirect to leader ").trim
    nodes.find(n => n.path.name == maybeId)
  }
}
