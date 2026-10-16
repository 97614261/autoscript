# Image processing block insertion: 2026-10-14

## Implemented

[ADR0102](../adr/0102-reversible-image-processing-pipelines.md) extends the shared
workbench with explicit processing-sequence insertion.

- Each preview version owns pixels plus its complete immutable operation list.
  Bitmap-history eviction does not lose earlier operations needed to reproduce a
  retained result; undo/redo restores both. The pipeline is bounded to 64 steps.
- Screenshot and declared-image library origins remain distinct. The insertion
  sheet explicitly chooses live screenshot versus the known original resource,
  plus two distinct, accessible, declared image variables. No hidden variables
  are created. A library opened outside an editor remains a save/test workspace.
- Starting parity guarantees the chosen final output slot. Each transform/crop
  creates its successor before releasing the previous frame. The final frame is
  deliberately retained for subsequent blocks **in the same task**; the UI tells
  the user to release it after use. This is not a cross-task/cross-run image value.
- Added screen.loadimage: real catalog/schema/compiler/configuration support for
  the existing Screen.loadImage API (opcode5003), not a new runtime API. It needs
  vision.template, a declared canonical image resource and an image output.
  Unknown/extra fields, path traversal, absent/wrong-type variables, missing
  capability and a scalar parameter shadowing the image output are rejected.
- Exact candidate node identities are staged without live-draft mutation. Before
  one undoable commit, the combined candidate drafts compile and formal project,
  all current drafts, selection, capability and runtime generation/state are
  rechecked. Position supports before/after/actual child/bottom. Failure/cancel
  leaves the current preview and editor intact; no implicit run or touch action.
- Both actual editor and retained floating-editor routes use the same callback;
  screenshot and editor-owned library workbenches expose the entry. The normal
  resource registration/packaging path already carries all declared images.

## Evidence

- New Kotlin tests cover frame-slot parity and release ordering, exact crop/resize
  arguments, no automatic capture/touch tail, complete operation history after
  eviction, undeclared/wrong-type variables, candidate identity validation,
  insertion positions, one undo, stale selection, unavailable compiler and
  cancellation after a success reply. Studio: 667 cases, 0 failures/errors,
  1 existing skip in the final gate.
- Real Rust compiler/runtime integration executes registered image -> gray ->
  clockwise rotation -> crop -> getColor -> release using the existing frame
  pool. Expected RGB output is 0x646464 and the task's remaining resource count
  is zero. The second test rejects wrong resource/type/path/field/capability and
  parameter-shadow cases without requiring a screenshot capability for load alone.
- `scripts/check-rust.ps1 -Offline` passed format, strict Clippy, workspace tests,
  schema validation and contract comparison. Flow-compiler: 116 cases. Catalog:
  175 visual blocks; runtime APIs remain 159; 185 schemas, 522 JSON documents.
- Final `build.cmd -Verify` passed in 2m43s (1075 tasks), including Studio tests,
  Lint, Debug packaging, ABI/model/ZIP and repository checks. Lint: 0 errors,
  36 warnings, 16 hints. MuMu 0 update installation succeeded with data retained.
  No private script was executed; synthetic host execution is not device evidence.
- Final full/development APK: 178,334,244 bytes; compressed payload: 172,337,610;
  layout/signing/padding: 5,996,634. Incremental layout observations are not
  optional-provider savings or inference performance conclusions.

The first compilation caught a local-function forward-reference in the retained
floating route; the callback now uses its already available save path. The final
gate validates that repaired source and the later parameter-shadow boundary.

## Remaining

- Direct tested match/OCR tails were pending at this batch's gate. They are now
  implemented in the [follow-up batch](image-recognition-tails-2026-10-14.md);
  its host evidence does not replace device acceptance of the complete workflow.
- Android UI/rendered/gesture, orientation/source-size, bitmap-alpha and stop/
  cancellation/device acceptance; standalone artifact execution of synthetic
  pipelines on the supported ABI/API matrix.
- Optional-provider import/selection/actual runtime loading/availability and
  condition-separated performance evidence. Studio remains full. No downloader,
  arbitrary plugin/filter or permission/capability auto-grant was added.
