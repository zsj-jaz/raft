package raft

import RaftNode.LogEntry

import java.io.{File, PrintWriter}
import scala.io.Source

class PersistentState(nodeId: String) {
  var currentTerm: Int         = 0
  var votedFor: Option[String] = None
  var log: List[LogEntry]      = List(LogEntry(0, "<dummy>", "", -1, None)) // 1-indexed

  private val filePath = s"raft_state_$nodeId.txt"

  // TO DO: not efficient to write entire log every time. Risky if the program crashes halfway through writing
  def persist(): Unit = {
    var fos: java.io.FileOutputStream = null
    var writer: java.io.PrintWriter   = null

    try {
      fos = new java.io.FileOutputStream(filePath)
      writer = new java.io.PrintWriter(fos)

      writer.println(currentTerm)
      writer.println(votedFor.getOrElse("null"))
      log.foreach { entry =>
        writer.println(s"${entry.term}|${entry.command}|${entry.clientId}|${entry.serialNum}")
      }

      writer.flush()
      fos.getFD.sync() // Ensures OS-level flush to disk
      println(s"[Persist] State saved to $filePath")

    } catch {
      case ex: Exception =>
        println(s"[Persist] Error writing to $filePath: ${ex.getMessage}")
        throw ex // re-throw or handle as needed

    } finally {
      if (writer != null) writer.close()
      else if (fos != null) fos.close() // ensure fos closed if writer creation failed
    }
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
