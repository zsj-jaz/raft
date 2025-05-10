package raft

import raft.RaftNode.LogEntry

import java.io.{File, PrintWriter}
import scala.io.Source

class PersistentState(nodeId: String) {
  var currentTerm: Int         = 0
  var votedFor: Option[String] = None
  var log: List[LogEntry]      = List(LogEntry(0, "<dummy>", "", -1, None)) // 1-indexed

  private val filePath = s"raft_state_$nodeId.txt"

  // TO DO: not efficient to write entire log every time. Risky if the program crashes halfway through writing
  def persist(): Unit = {
    val writer = new PrintWriter(new File(filePath))
    writer.println(currentTerm)
    writer.println(votedFor.getOrElse("null"))
    log.foreach { entry =>
      writer.println(s"${entry.term}|${entry.command}")
    }
    writer.close()
    println(s"[Persist] State saved to $filePath")
  }

  def load(): PersistentState = {
    val file = new File(filePath)
    if (!file.exists()) {
      println(s"[Load] No state file found at $filePath, using defaults")
      return this
    }

    val lines = Source.fromFile(filePath).getLines().toList
    currentTerm = lines.headOption.map(_.toInt).getOrElse(0)
    votedFor = lines.lift(1).flatMap(v => if (v == "null") None else Some(v))

    val loadedLog = lines.drop(2).flatMap { case line =>
      val parts = line.split('|')
      if (parts.length == 4) {
        val term      = parts(0).toInt
        val command   = parts(1)
        val clientId  = parts(2)
        val serialNum = parts(3).toInt
        Some(LogEntry(term, command, clientId, serialNum, None))
      } else {
        None
      }
    }

    // Ensure dummy entry is present
    log =
      if (loadedLog.nonEmpty && loadedLog.head.term == 0 && loadedLog.head.command == "<dummy>") {
        loadedLog
      } else {
        LogEntry(0, "<dummy>", "", -1, None) :: loadedLog
      }

    println(
      s"[Load] State loaded from $filePath (term=$currentTerm, votedFor=$votedFor, log=${log.size} entries)"
    )
    this
  }

}
