package raft

import java.io.{File, FileWriter}
import RaftNode.ClientResponse

trait StateMachine {

  def applyCommand(
      command: String,
      clientId: String,
      serialNum: Int
  ): (Boolean, ClientResponse)
}
