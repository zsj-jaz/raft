// package raft

// import akka.actor.typed.scaladsl.Behaviors
// import akka.actor.typed.{ActorRef, Behavior}
// import raft.RaftNode._

// object RaftProxy {

//   def apply(id: String, target: ActorRef[Command]): Behavior[Command] =
//     Behaviors.setup { ctx =>
//       var drops: Set[(String, Class[_])] = Set.empty

//       def shouldDrop(m: Command): Boolean =
//         extractSenderId(m).exists { senderId =>
//           drops.exists { case (from, clazz) => from == senderId && clazz.isInstance(m) }
//         }

//       Behaviors.receiveMessage {
//         case DropIf(from, clazz) =>
//           ctx.log.debug(s"[$id] will now drop ${clazz.getSimpleName} from $from")
//           drops += (from -> clazz)
//           Behaviors.same

//         case ClearDrops                              =>
//           ctx.log.debug(s"[$id] clearing all drop rules")
//           drops = Set.empty
//           Behaviors.same

//         // Raft RPCs
//         case ae @ AppendEntries(_, _, _, _, _, _, _) =>
//           if (!shouldDrop(ae)) {
//             target ! ae.copy(replyTo = ctx.self)
//           }
//           Behaviors.same

//         case aer @ AppendEntriesResponse(_, _, _, _) =>
//           if (!shouldDrop(aer)) {
//             // wrap response with senderId so leader can properly key `nextIndex`
//             target ! AppendEntriesResponse(aer.term, aer.success, ctx.self, aer.matchIndexIfSuccess)
//           }
//           Behaviors.same

//         case rv @ RequestVote(_, _, _, _, _) =>
//           if (!shouldDrop(rv)) target ! rv.copy(replyTo = ctx.self)
//           Behaviors.same

//         case vr: VoteResponse =>
//           if (!shouldDrop(vr)) target ! vr
//           Behaviors.same

//         case other =>
//           if (!shouldDrop(other)) target ! other
//           Behaviors.same
//       }
//     }

//   private def extractSenderId(cmd: Command): Option[String] = cmd match {
//     case AppendEntries(_, leaderId, _, _, _, _, _) => Some(leaderId)
//     case AppendEntriesResponse(_, _, sender, _)    => Some(sender.path.name.stripPrefix("proxy-"))
//     case RequestVote(_, candidateId, _, _, _)      => Some(candidateId)
//     case _                                         => None
//   }
// }
