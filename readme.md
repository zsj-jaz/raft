## Consulted Source

- MIT 6.824: Distributed Systems (Spring 2020) — Lecture 6 and 7 on Raft

## Issues and Fixes

### Single-node cluster edge case

Originally, my Raft implementation didn’t support a single-node cluster. I made a quick fix (not fully confident it's complete, but it works):

- **Immediate vote check**: When a node starts an election, it checks if it already has a majority (i.e., just itself), instead of waiting for VoteResponses from nonexistent peers.
- **Immediate commit**: Once elected, the leader immediately calls `tryAdvanceCommitIndex()` after sending the initial AppendEntries, so client requests can be committed even with no followers.

### `pendingClientAcks` should be persistant

To notify a client of a write failure, I'm using `pendingClientAcks` to record where in the Raft log the client’s operation appears when it's inserted. If the operation that ends up at that index is not the one the client submitted, a failure is returned.

But to make that work correctly, we need to persist `pendingClientAcks`. Otherwise, since the leader Raft log is append-only within the same leadership term, without persistence, a leader crash would lose this state and the client would never receive a failure response.

For now, this tracking is still volatile and not persisted.

