package raft

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{Behaviors, ActorContext, TimerScheduler}
import scala.concurrent.duration._
import scala.util.Random
import RaftNode.{
  ClientRequest,
  WriteRequest,
  ReadRequest,
  ClientResponse,
  WriteResponse,
  ReadResponse,
  Command,
  Tick,
  Retry
}

object LongLivedClient {

  def apply(
      baseKey: String,
      nodes: Seq[ActorRef[Command]]
  ): Behavior[ClientResponse] = {
    Behaviors.withTimers { timers =>
      Behaviors.setup { context =>
        val clientId  = context.self.path.name
        var serialNum = 1

        // Track last request and current known leader
        var lastRequest: ClientRequest        =
          WriteRequest("INIT", clientId = "dummy", serialNum = 0, context.self)
        var leader: Option[ActorRef[Command]] = None

        def pickRandomNode(nodes: Seq[ActorRef[Command]]): ActorRef[Command] = {
          val rnd = scala.util.Random
          nodes(rnd.nextInt(nodes.length))
        }

        def sendNextRequest(): Unit = {
          val isFirst  = serialNum == 1
          val isWrite  = isFirst || Random.nextBoolean()
          val writeKey = s"$baseKey$serialNum"
          val readKey  = s"$baseKey${serialNum - 1}"

          leader match {
            case Some(node) =>
              if (isWrite) {
                lastRequest =
                  WriteRequest(s"SET $writeKey=$serialNum", clientId, serialNum, context.self)
                context.log.info(
                  s"[LongLived-$clientId] Sending WRITE $writeKey=$serialNum to ${node.path.name}"
                )
              } else {
                lastRequest = ReadRequest(readKey, clientId, serialNum, context.self)
                context.log.info(
                  s"[LongLived-$clientId] Sending READ $readKey to ${node.path.name}"
                )
              }
              node ! lastRequest
              timers.startSingleTimer("retry", Retry, 5.seconds)

            case None =>
              context.log.warn(s"[LongLived-$clientId] No leader known; skipping request")
          }
        }

        def retry(): Unit = {
          leader match {
            case Some(node) =>
              context.log.info(s"[LongLived-$clientId] Retrying last request to ${node.path.name}")
              node ! lastRequest
              timers.startSingleTimer("retry", Retry, 5.seconds)

            case None =>
              context.log.warn(s"[LongLived-$clientId] No leader known; cannot retry")
          }
        }

        def handleWriteResponse(success: Boolean, msg: String): Unit = {
          if (success) {
            context.log.info(s"[LongLived-$clientId] Write success: $msg")
            serialNum += 1
            timers.cancel("retry")
            timers.startSingleTimer("tick", Tick, 10.seconds)
          } else if (msg.startsWith("Redirect to leader")) {
            context.log.info(s"[LongLived-$clientId] Write redirected: $msg")
            extractRedirection(msg, nodes).foreach { newLeader =>
              leader = Some(newLeader)
              retry()
            }
          } else if (msg.startsWith("Stale request")) {
            context.log.warn(s"[LongLived-$clientId] Stale request: $msg")
            serialNum += 1
            timers.cancel("retry")
            timers.startSingleTimer("tick", Tick, 10.seconds)
          } else {
            context.log.error(s"[LongLived-$clientId] Write failed: $msg")
            // Handle unexpected failure
            timers.startSingleTimer("retry", Retry, 5.seconds)
          }
        }

        def handleReadResponse(value: String): Unit = {
          if (value.startsWith("Redirect to leader")) {
            context.log.info(s"[LongLived-$clientId] Read redirected: $value")
            extractRedirection(value, nodes).foreach { newLeader =>
              leader = Some(newLeader)
              retry()
            }
          } else {
            context.log.info(s"[LongLived-$clientId] Read success: $value")
            timers.cancel("retry")
            timers.startSingleTimer("tick", Tick, 10.seconds)
          }
        }

        def extractRedirection(
            msg: String,
            nodes: Seq[ActorRef[Command]]
        ): Option[ActorRef[Command]] = {
          val maybeId = msg.stripPrefix("Redirect to leader ").trim
          if (maybeId == "unknown") {
            return Some(pickRandomNode(nodes))
          }
          nodes.find(_.path.name == maybeId)
        }

        leader = Some(pickRandomNode(nodes))
        sendNextRequest()

        Behaviors.receiveMessage {
          case WriteResponse(success, msg) =>
            handleWriteResponse(success, msg)
            Behaviors.same

          case ReadResponse(value) =>
            handleReadResponse(value)
            Behaviors.same

          case Retry =>
            retry()
            Behaviors.same

          case Tick =>
            sendNextRequest()
            Behaviors.same
        }
      }
    }
  }
}
