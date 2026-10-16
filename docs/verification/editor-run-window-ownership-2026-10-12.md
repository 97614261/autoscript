# Editor run and single-step verification, 2026-10-12

Decision: ADR 0083, user's latest editor-run behavior.

## Implemented

- Editor Run and idle Single Step have explicit separate entry values.
- Editor startup and execution no longer suppress the editor or its ball.
- The first footer slot changes Run to Stop; Pause/Continue uses the third slot.
- Both editor-ball implementations only restore the editor. Removed the unused
  local runtime menu and editor terminal-return gate, retaining the formal launch gate.
- Session-bound startup overrides the runtime ball for Studio only: editor tests
  off, formal Launch on. Runner retains its own preference. Closing an active
  editor hands control back to the runtime ball; notification controls remain.
- Idle selected-node tests omit project startup delay; paused steps retain the VM.
- Accepted startup feedback distinguishes submission from observed execution and
  termination/failure. It is not combined with the draft-save success message.
- The fallback editor uses the same policy and independent priority-stop guard.

## Checks

- `build.cmd -Verify -MuMuManagerPath D:\soft\MuMuPlayer\nx_main\MuMuManager.exe`:
  tests, Lint and Debug build successful; installed on MuMu instance 0 at
  `127.0.0.1:16384`, retaining existing application data.
- Studio: 569 unit tests, zero failures/errors.
- Lint: zero errors, 32 warnings and 17 hints (existing deprecations/tooling).
- `cargo test -p studio-compiler-jni --offline selected_`: 2 tests passed,
  including actual selected-only Lua execution and container/dependency behavior.
- `git diff --check`: no whitespace errors (repository CRLF notices only).

Device touch execution and visual interaction have not been manually validated
in this batch. No private user script was run. Selected tests still require their
declared dependencies; isolating a frame consumer without its capture or an
external loop-dependent node must not be described as successful execution.
