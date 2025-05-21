package raft

import java.io.{File, FileWriter, BufferedWriter}
import scala.collection.mutable
import RaftNode.WriteResponse

class FileAppendingStateMachine(nodeId: String) extends StateMachine {

  private val file = new File(s"state_machine_output_$nodeId.txt")
  // State machine should start with an empty file, and replay the raft log to reconstruct the file.
  // we don't implement snapshot.
  new FileWriter(file, false).close()

  private val clientRecords =
    mutable.Map
      .empty[String, (Int, WriteResponse)] // clientId -> (lastSerial, response(sucess, message))

  private val kvStore = mutable.Map.empty[String, String]

  override def applyCommand(
      command: String,
      clientId: String,
      serialNum: Int
  ): (Boolean, WriteResponse) = {
    clientRecords.get(clientId) match {
      case Some((lastSerial, writeResponse)) if serialNum <= lastSerial =>
        // Duplicate or stale request — return cached WriteResponse
        println(
          s"[${nodeId}] Duplicate or stale request from client $clientId: $command"
        )
        (false, writeResponse)

      case _ =>
        // New command — apply it and record the response
        val result = {
          if (command.startsWith("SET ")) {
            val remainder = command.stripPrefix("SET ").trim
            val parts     = remainder.split("=", 2)

            if (parts.length == 2) {
              val key   = parts(0).trim
              val value = parts(1).trim
              kvStore(key) = value
              s"OK: SET $key = $value"
            } else {
              s"INVALID SET COMMAND: $command"
            }
          } else {
            s"UNKNOWN COMMAND: $command"
          }
        }

        val bw = new BufferedWriter(new FileWriter(file, true))
        bw.write(s"$clientId:$serialNum -> $command")
        bw.newLine()
        bw.close()

        clientRecords(clientId) = (serialNum, WriteResponse(success = true, message = result))
        (true, WriteResponse(success = true, message = result))
    }
  }

  def readKey(key: String): String = {
    kvStore.get(key).map(v => s"$key=$v").getOrElse(s"$key not found")
  }

}
