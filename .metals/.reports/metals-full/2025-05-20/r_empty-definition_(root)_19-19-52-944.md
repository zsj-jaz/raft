error id: file://<WORKSPACE>/src/main/scala/raft/LongLivedClient.scala:`<none>`.
file://<WORKSPACE>/src/main/scala/raft/LongLivedClient.scala
empty definition using pc, found symbol in pc: `<none>`.
empty definition using semanticdb
empty definition using fallback
non-local guesses:
	 -scala/concurrent/duration/key.
	 -scala/concurrent/duration/key#
	 -scala/concurrent/duration/key().
	 -key.
	 -key#
	 -key().
	 -scala/Predef.key.
	 -scala/Predef.key#
	 -scala/Predef.key().
offset: 875
uri: file://<WORKSPACE>/src/main/scala/raft/LongLivedClient.scala
text:
```scala
package raft

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{Behaviors, ActorContext, TimerScheduler}
import scala.concurrent.duration._
import RaftNode.{WriteRequest, ReadRequest, WriteResponse, ReadResponse, Command}

object LongLivedClient {

  def apply(
      baseKey: String,
      nodes: Seq[ActorRef[Command]]
  ): Behavior[Any] = {
    Behaviors.withTimers { timers =>
      Behaviors.setup { context =>
        val clientId  = context.self.path.name
        var serialNum = 1

        def Tick(): Unit = {
          context.log.info(s"[LongLived-$clientId] Tick")
          request = generateRequest()
          sendTo = pickRandomNode(nodes)
          send(request, Some(sendTo))
        }

        def generateRequest(): Command = {
          val isWrite = Random.nextBoolean()
          if (isWrite) {
            WriteRequest(key@@, clientId, serialNum, context.self)
          } else {
            ReadRequest(key, clientId, serialNum, context.self)
          }
        }

        def send(request: Command, sendTo: Option[ActorRef[Command]]): Unit = {
          sendTo match {
            case Some(node) =>
              context.log.info(s"[LongLived-$clientId] Sending request to $node")
              node ! request
            case None       =>
              context.log.info(s"[LongLived-$clientId] No nodes available to send request")
          }
        }

        def handleWriteResponse(success: Boolean, msg: String): Unit = {
          if (success) {
            context.log.info(s"[LongLived-$clientId] Write success: $msg")
            serialNum += 1
            timers.startSingleTimer("tick", Tick(), 20.seconds)
          } else {
            context.log.info(s"[LongLived-$clientId] Write redirected: $msg")
            val leader = extractRedirection(msg, nodes).getOrElse(pickRandomNode(nodes))
            send(request, Some(leader))
          }
        }

        def handleReadResponse(value: String): Unit = {
          if (success) {
            context.log.info(s"[LongLived-$clientId] Read success: — $value")
            timers.startSingleTimer("tick", Tick(), 20.seconds)
          } else if (value.startsWith("Redirect to leader")) {
            context.log.info(s"[LongLived-$clientId] Read redirected: $value")
            val leader = extractRedirection(value, nodes).getOrElse(pickRandomNode(nodes))
            send(request, Some(leader))
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

        Tick()

        Behaviors.receiveMessage {
          case WriteResponse(success, msg) =>
            handleWriteResponse(success, msg)
            Behaviors.same

          case ReadResponse(value) =>
            handleReadResponse(value)
            Behaviors.same
        }
      }
    }
  }
}

```


#### Short summary: 

empty definition using pc, found symbol in pc: `<none>`.