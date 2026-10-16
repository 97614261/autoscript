# Continuous edit window and paste positions, 2026-10-12

Decision: ADR 0086; user explicitly requested list indentation, a before-row paste
option alongside after/child options, and a window retained after each operation.

- The edit list indents checkbox and label together by actual projected depth,
  with guide lines and bounded horizontal scrolling for deeper trees.
- The paste picker offers before, after and actual child branches, including on
  leaf rows. Requests bind project/flow/source/selection and reject stale context.
- Forest paste-before preserves order and selects its first copied root. Repeated
  paste cannot interleave forests; one paste remains one undo transaction.
- Final cross-root reference rewriting preserves already-assigned structural
  node/block/parent/child identities, avoiding a cycle when pasting a copied
  container within its original branch.
- Automatic saving/validation disables commands without clearing the edit window.
  Detail dialogs layer above the retained window. Running and explicit dismissal
  retain their existing close behavior; mutation safety checks remain in the host.

Verification: `build.cmd -Verify -MuMuManagerPath
D:\soft\MuMuPlayer\nx_main\MuMuManager.exe` passed unit tests, Lint and Debug
build. Studio has 579 tests, zero failures. Installed on MuMu instance 0 with
application data retained. `git diff --check` passed (CRLF notices only).

New tests cover busy-window policy, indent bounds/wiring, paste options, stale
requests, repeated forest order and undo, and nested subtree depth. No private
script was executed; visual device acceptance remains for the user to check.
