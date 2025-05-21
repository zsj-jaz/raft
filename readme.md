## Consulted Source

- MIT 6.824: Distributed Systems (Spring 2020) — Lecture 6 and 7 on Raft

## Issues and Fixes

### Single-node cluster edge case

Originally, my Raft implementation didn’t support a single-node cluster. I made a quick fix (not fully confident it's complete, but it works):

- **Immediate vote check**: When a node starts an election, it checks if it already has a majority (i.e., just itself), instead of waiting for VoteResponses from nonexistent peers.
- **Immediate commit**: Once elected, the leader immediately calls `tryAdvanceCommitIndex()` after sending the initial AppendEntries, so client requests can be committed even with no followers.


### Election Timer not cancelled when transit from candidate to leader

When a node becomes the leader, the election timer from the candidate state isn’t explicitly cancelled. This means the leader can still receive ElectionTimeout messages. While this doesn’t break correctness (leaders ignore election timeouts), it’s inefficient and potentially confusing. The timer should ideally be cancelled upon becoming leader.