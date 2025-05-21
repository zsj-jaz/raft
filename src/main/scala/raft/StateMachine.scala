package raft

import java.io.{File, FileWriter}
import RaftNode.WriteResponse

trait StateMachine {

  def applyCommand(
      command: String,
      clientId: String,
      serialNum: Int
  ): (Boolean, WriteResponse)
}
