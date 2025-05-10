package raft

import java.io.{File, FileWriter, BufferedWriter}
import scala.collection.mutable
import raft.RaftNode.ClientResponse

class FileAppendingStateMachine(nodeId: String) extends StateMachine {

  private val file = new File(s"state_machine_output_$nodeId.txt")
  // State machine should start with an empty file, and replay the raft log to reconstruct the file.
  // we don't implement snapshot.
  new FileWriter(file, false).close()

  private val clientRecords =
    mutable.Map
      .empty[String, (Int, ClientResponse)] // clientId -> (lastSerial, response(sucess, message))

  override def applyCommand(
      command: String,
      clientId: String,
      serialNum: Int
  ): (Boolean, ClientResponse) = {
    clientRecords.get(clientId) match {
      case Some((lastSerial, clientResponse)) if serialNum <= lastSerial =>
        // Duplicate or stale request — return cached ClientResponse
        println(
          s"[${nodeId}] Duplicate or stale request from client $clientId: $command"
        )
        (false, clientResponse)

      case _ =>
        // New command — apply it and record the response
        val result = s"OK: $command"

        val bw = new BufferedWriter(new FileWriter(file, true))
        bw.write(s"$clientId:$serialNum -> $command")
        bw.newLine()
        bw.close()

        clientRecords(clientId) = (serialNum, ClientResponse(success = true, message = result))
        (true, ClientResponse(success = true, message = result))
    }
  }

}
