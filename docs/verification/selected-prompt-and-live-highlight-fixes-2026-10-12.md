# Selected prompt and live-highlight fixes, 2026-10-12

Decision: ADR 0084.

## Findings

1. `newSessionGeneration()` already generates nonzero signed random longs.
   `RuntimeExecutionPosition` required a positive generation and discarded valid
   negative-session messages before the editor could receive them. Validation now
   accepts nonzero generations, retaining every identity/stale-run check.
2. An isolated persistent-prompt node finishes immediately. End-of-run cleanup
   closes that window and rejects its queued display before it can be drawn.
   Selected-subtree tests now freeze an editor-only timed-preview wrapper, keeping
   style, duration, master switch, stop cleanup and formal-run semantics.

## Verification

- Runtime-client regression covers negative identities including `Long.MIN_VALUE`,
  positive identity, zero rejection, mismatch and ended/stopped-run rejection.
- Studio plan tests cover selected preview insertion, disabled master switch,
  unchanged generated artifacts and omitted single-test startup delay.
- `cargo test -p studio-compiler-jni --offline selected_`: 3 actual Lua/isolated
  compiler tests passed. Prompt test confirms normal `window` versus test `toast`,
  project 100x30 geometry and 900ms duration in the emitted event.
- `cargo clippy -p studio-compiler-jni --all-targets --offline -- -D warnings`:
  passed. `cargo fmt --all` and `git diff --check` passed.
- `build.cmd -Verify -MuMuManagerPath D:\soft\MuMuPlayer\nx_main\MuMuManager.exe`:
  Android tests, Lint and Debug build passed. Studio has 569 tests with zero
  failures/errors; Runtime-client position gate has 2 tests with zero failures.
  Updated MuMu instance 0 successfully, retaining application data.

Device inspection was read-only, limited to the reported project's capabilities,
prompt settings, generated-code feature presence and diagnostic/metadata records.
No private script text or prompt payload is retained here, and no private script
was executed. Visual device behavior remains a separate user acceptance check.
Fast node positions continue to be coalesced; no per-row animation delay is added.
