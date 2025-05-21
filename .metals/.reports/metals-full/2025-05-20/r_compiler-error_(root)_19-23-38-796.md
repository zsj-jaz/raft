file://<WORKSPACE>/src/main/scala/raft/LongLivedClient.scala
### java.lang.AssertionError: assertion failed

occurred in the presentation compiler.

presentation compiler configuration:


action parameters:
uri: file://<WORKSPACE>/src/main/scala/raft/LongLivedClient.scala
text:
```scala
package raft

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{Behaviors, ActorContext, TimerScheduler}
import scala.concurrent.duration._
import scala.util.Random
import RaftNode.{WriteRequest, ReadRequest, WriteResponse, ReadResponse, Command}

// Sealed trait to hold a client request

final case class WrappedWrite(request: WriteRequest) extends ClientRequest
final case class WrappedRead(request: ReadRequest)   extends ClientRequest

object LongLivedClient {

  def apply(
      baseKey: String,
      nodes: Seq[ActorRef[Command]]
  ): Behavior[Any] = {
    Behaviors.withTimers { timers =>
      Behaviors.setup { context =>
        val clientId  = context.self.path.name
        var serialNum = 1

        // Track last request and target node to support retries
        var lastRequest: ClientRequest            = null
        var lastTarget: Option[ActorRef[Command]] = None

        def Tick(): Unit = {
          context.log.info(s"[LongLived-$clientId] Tick")
          val request = generateRequest()
          val node    = pickRandomNode(nodes)
          lastRequest = request
          lastTarget = Some(node)
          send(request, Some(node))
        }

        def generateRequest(): ClientRequest = {
          val isWrite = Random.nextBoolean()
          val key     = s"$baseKey$serialNum"
          if (isWrite) {
            WrappedWrite(WriteRequest(s"SET $key=$serialNum", clientId, serialNum, context.self))
          } else {
            WrappedRead(ReadRequest(key, clientId, serialNum, context.self))
          }
        }

        def send(request: ClientRequest, sendTo: Option[ActorRef[Command]]): Unit = {
          sendTo match {
            case Some(node) =>
              context.log.info(s"[LongLived-$clientId] Sending request to ${node.path.name}")
              request match {
                case WrappedWrite(req) => node ! req
                case WrappedRead(req)  => node ! req
              }
            case None       =>
              context.log.info(s"[LongLived-$clientId] No nodes available to send request")
          }
        }

        def handleWriteResponse(success: Boolean, msg: String): Unit = {
          if (success) {
            context.log.info(s"[LongLived-$clientId] Write success: $msg")
            serialNum += 1
            timers.startSingleTimer("tick", () => Tick(), 20.seconds)
          } else if (msg.startsWith("Redirect to leader")) {
            context.log.info(s"[LongLived-$clientId] Write redirected: $msg")
            val leader = extractRedirection(msg, nodes).getOrElse(pickRandomNode(nodes))
            lastTarget = Some(leader)
            send(lastRequest, Some(leader))
          }
        }

        def handleReadResponse(value: String): Unit = {
          if (value.startsWith("Redirect to leader")) {
            context.log.info(s"[LongLived-$clientId] Read redirected: $value")
            val leader = extractRedirection(value, nodes).getOrElse(pickRandomNode(nodes))
            lastTarget = Some(leader)
            send(lastRequest, Some(leader))
          } else {
            context.log.info(s"[LongLived-$clientId] Read success: $value")
            timers.startSingleTimer("tick", () => Tick(), 20.seconds)
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



#### Error stacktrace:

```
scala.runtime.Scala3RunTime$.assertFailed(Scala3RunTime.scala:11)
	dotty.tools.dotc.core.Annotations$LazyAnnotation.tree(Annotations.scala:139)
	dotty.tools.dotc.core.Annotations$Annotation$Child$.unapply(Annotations.scala:245)
	dotty.tools.dotc.typer.Namer.insertInto$1(Namer.scala:476)
	dotty.tools.dotc.typer.Namer.addChild(Namer.scala:487)
	dotty.tools.dotc.typer.Namer$Completer.register$1(Namer.scala:931)
	dotty.tools.dotc.typer.Namer$Completer.registerIfChildInCreationContext$$anonfun$1(Namer.scala:940)
	scala.runtime.function.JProcedure1.apply(JProcedure1.java:15)
	scala.runtime.function.JProcedure1.apply(JProcedure1.java:10)
	scala.collection.immutable.List.foreach(List.scala:334)
	dotty.tools.dotc.typer.Namer$Completer.registerIfChildInCreationContext(Namer.scala:940)
	dotty.tools.dotc.typer.Namer$Completer.complete(Namer.scala:829)
	dotty.tools.dotc.core.SymDenotations$SymDenotation.completeFrom(SymDenotations.scala:174)
	dotty.tools.dotc.core.Denotations$Denotation.completeInfo$1(Denotations.scala:188)
	dotty.tools.dotc.core.Denotations$Denotation.info(Denotations.scala:190)
	dotty.tools.dotc.core.Types$NamedType.info(Types.scala:2363)
	dotty.tools.dotc.core.Types$TermLambda.dotty$tools$dotc$core$Types$TermLambda$$_$compute$1(Types.scala:3893)
	dotty.tools.dotc.core.Types$TermLambda.foldArgs$2(Types.scala:3900)
	dotty.tools.dotc.core.Types$TermLambda.dotty$tools$dotc$core$Types$TermLambda$$_$compute$1(Types.scala:4520)
	dotty.tools.dotc.core.Types$TermLambda.dotty$tools$dotc$core$Types$TermLambda$$depStatus(Types.scala:3920)
	dotty.tools.dotc.core.Types$TermLambda.dependencyStatus(Types.scala:3934)
	dotty.tools.dotc.core.Types$TermLambda.isResultDependent(Types.scala:3956)
	dotty.tools.dotc.core.Types$TermLambda.isResultDependent$(Types.scala:3850)
	dotty.tools.dotc.core.Types$MethodType.isResultDependent(Types.scala:3995)
	dotty.tools.dotc.typer.TypeAssigner.assignType(TypeAssigner.scala:295)
	dotty.tools.dotc.typer.TypeAssigner.assignType$(TypeAssigner.scala:16)
	dotty.tools.dotc.typer.Typer.assignType(Typer.scala:116)
	dotty.tools.dotc.ast.tpd$.Apply(tpd.scala:47)
	dotty.tools.dotc.ast.tpd$TreeOps$.appliedToTermArgs$extension(tpd.scala:957)
	dotty.tools.dotc.ast.tpd$.New(tpd.scala:543)
	dotty.tools.dotc.ast.tpd$.New(tpd.scala:534)
	dotty.tools.dotc.core.Annotations$Annotation$Child$.makeChildLater$1(Annotations.scala:234)
	dotty.tools.dotc.core.Annotations$Annotation$Child$.later$$anonfun$1(Annotations.scala:237)
	dotty.tools.dotc.core.Annotations$LazyAnnotation.tree(Annotations.scala:143)
	dotty.tools.dotc.core.Annotations$Annotation$Child$.unapply(Annotations.scala:245)
	dotty.tools.dotc.typer.Namer.insertInto$1(Namer.scala:476)
	dotty.tools.dotc.typer.Namer.addChild(Namer.scala:487)
	dotty.tools.dotc.typer.Namer$Completer.register$1(Namer.scala:931)
	dotty.tools.dotc.typer.Namer$Completer.registerIfChildInCreationContext$$anonfun$1(Namer.scala:940)
	scala.runtime.function.JProcedure1.apply(JProcedure1.java:15)
	scala.runtime.function.JProcedure1.apply(JProcedure1.java:10)
	scala.collection.immutable.List.foreach(List.scala:334)
	dotty.tools.dotc.typer.Namer$Completer.registerIfChildInCreationContext(Namer.scala:940)
	dotty.tools.dotc.typer.Namer$Completer.complete(Namer.scala:829)
	dotty.tools.dotc.core.SymDenotations$SymDenotation.completeFrom(SymDenotations.scala:174)
	dotty.tools.dotc.core.Denotations$Denotation.completeInfo$1(Denotations.scala:188)
	dotty.tools.dotc.core.Denotations$Denotation.info(Denotations.scala:190)
	dotty.tools.dotc.core.SymDenotations$SymDenotation.ensureCompleted(SymDenotations.scala:392)
	dotty.tools.dotc.typer.Typer.retrieveSym(Typer.scala:3067)
	dotty.tools.dotc.typer.Typer.typedNamed$1(Typer.scala:3092)
	dotty.tools.dotc.typer.Typer.typedUnadapted(Typer.scala:3206)
	dotty.tools.dotc.typer.Typer.typed(Typer.scala:3277)
	dotty.tools.dotc.typer.Typer.typed(Typer.scala:3281)
	dotty.tools.dotc.typer.Typer.traverse$1(Typer.scala:3303)
	dotty.tools.dotc.typer.Typer.typedStats(Typer.scala:3349)
	dotty.tools.dotc.typer.Typer.typedPackageDef(Typer.scala:2889)
	dotty.tools.dotc.typer.Typer.typedUnnamed$1(Typer.scala:3159)
	dotty.tools.dotc.typer.Typer.typedUnadapted(Typer.scala:3207)
	dotty.tools.dotc.typer.Typer.typed(Typer.scala:3277)
	dotty.tools.dotc.typer.Typer.typed(Typer.scala:3281)
	dotty.tools.dotc.typer.Typer.typedExpr(Typer.scala:3392)
	dotty.tools.dotc.typer.TyperPhase.typeCheck$$anonfun$1(TyperPhase.scala:45)
	scala.runtime.function.JProcedure1.apply(JProcedure1.java:15)
	scala.runtime.function.JProcedure1.apply(JProcedure1.java:10)
	dotty.tools.dotc.core.Phases$Phase.monitor(Phases.scala:463)
	dotty.tools.dotc.typer.TyperPhase.typeCheck(TyperPhase.scala:51)
	dotty.tools.dotc.typer.TyperPhase.$anonfun$4(TyperPhase.scala:97)
	scala.collection.Iterator$$anon$6.hasNext(Iterator.scala:479)
	scala.collection.Iterator$$anon$9.hasNext(Iterator.scala:583)
	scala.collection.immutable.List.prependedAll(List.scala:152)
	scala.collection.immutable.List$.from(List.scala:685)
	scala.collection.immutable.List$.from(List.scala:682)
	scala.collection.IterableOps$WithFilter.map(Iterable.scala:900)
	dotty.tools.dotc.typer.TyperPhase.runOn(TyperPhase.scala:96)
	dotty.tools.dotc.Run.runPhases$1$$anonfun$1(Run.scala:315)
	scala.runtime.function.JProcedure1.apply(JProcedure1.java:15)
	scala.runtime.function.JProcedure1.apply(JProcedure1.java:10)
	scala.collection.ArrayOps$.foreach$extension(ArrayOps.scala:1323)
	dotty.tools.dotc.Run.runPhases$1(Run.scala:308)
	dotty.tools.dotc.Run.compileUnits$$anonfun$1(Run.scala:349)
	dotty.tools.dotc.Run.compileUnits$$anonfun$adapted$1(Run.scala:358)
	dotty.tools.dotc.util.Stats$.maybeMonitored(Stats.scala:69)
	dotty.tools.dotc.Run.compileUnits(Run.scala:358)
	dotty.tools.dotc.Run.compileSources(Run.scala:261)
	dotty.tools.dotc.interactive.InteractiveDriver.run(InteractiveDriver.scala:161)
	dotty.tools.pc.CachingDriver.run(CachingDriver.scala:45)
	dotty.tools.pc.WithCompilationUnit.<init>(WithCompilationUnit.scala:31)
	dotty.tools.pc.SimpleCollector.<init>(PcCollector.scala:351)
	dotty.tools.pc.PcSemanticTokensProvider$Collector$.<init>(PcSemanticTokensProvider.scala:63)
	dotty.tools.pc.PcSemanticTokensProvider.Collector$lzyINIT1(PcSemanticTokensProvider.scala:63)
	dotty.tools.pc.PcSemanticTokensProvider.Collector(PcSemanticTokensProvider.scala:63)
	dotty.tools.pc.PcSemanticTokensProvider.provide(PcSemanticTokensProvider.scala:88)
	dotty.tools.pc.ScalaPresentationCompiler.semanticTokens$$anonfun$1(ScalaPresentationCompiler.scala:111)
```
#### Short summary: 

java.lang.AssertionError: assertion failed