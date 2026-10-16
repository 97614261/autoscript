# Fast-row highlight verification, 2026-10-12

Decision: ADR 0085. User clarified the failing behavior was row highlighting,
not missing dynamic-prompt output.

## Root cause and implementation

The executor can enter a dynamic-prompt checkpoint and the next image checkpoint
inside one drive. Sampling only the final position after the pump, followed by
50ms latest-only coalescing, loses the fast row even with valid signed generations.

- Rust retains at most 32 entered identities, drained atomically with the latest
  position through the existing JNI getter.
- Service and client merge bounded pending batches. Sequence counters reject
  older samples within the same run; existing session/project/run gates remain.
- Protocol 24 carries a sample sequence and at most 32 paired flow/node identities.
- Editor display buffers unique pending rows, with a 75ms display-only interval.
  Non-current trail rows are labelled as recently executed; the latest live row
  remains distinct. Pause/stop/disconnect and worker cancellation clear the buffer.
- Existing ancestor unfolding and row scrolling consume these same identities;
  no editing selection, node arguments, source or runtime logs are rewritten.
- No delays are inserted into the Lua VM or automation commands.

## Checks

- `cargo fmt --all`, full `cargo test --workspace --offline`, and full
  `cargo clippy --workspace --all-targets --offline -- -D warnings` passed.
- Engine regression actually enters image → dynamic prompt → image in one pump,
  with trace/metrics disabled; latest-only position is the final image but the
  bounded trail retains the prompt. Another test covers 100-node overflow.
- Client tests cover pending-batch merge limits, sample/run mismatch and signed
  identities. Studio tests cover visible prompt traversal, fair duplicate handling,
  new-run/clear behavior and cancellation without a late display.
- `build.cmd -Verify -MuMuManagerPath D:\soft\MuMuPlayer\nx_main\MuMuManager.exe`
  passed Android tests, Lint and Debug build; installed on MuMu instance 0,
  preserving application data. Studio has 573 tests with zero failures; the
  display-trail suite has 4 tests and the client position-gate suite has 3.

Only the reported project's diagnostic metadata and block type/channel metadata
were inspected read-only. No private expressions or prompt contents are recorded
here, and no user script was executed by the assistant. Device visual acceptance
is separate from local regression tests. Buffer overflow and UI frame coalescing
mean this feature does not guarantee drawing every node of arbitrarily fast loops.
