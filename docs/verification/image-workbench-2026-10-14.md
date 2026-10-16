# Shared image workbench: 2026-10-14

## Implemented increment

See [ADR0101](../adr/0101-shared-image-processing-workbench.md).

- Screenshot tools expose a processing action; the image library opens the same
  workspace from the current screenshot or a declared image row. File decoding
  checks bounds before allocation and does not silently sample or density-scale.
- The borrowed original is unchanged. Processing has clockwise 90/180/270
  rotation, horizontal/vertical mirror, integer-weight gray, half-open crop and
  explicit nearest-neighbor resize. Invalid dimensions and output budgets reject
  work without modifying the current image.
- Eight retained edits / 32 MiB including the original bound history. Undo/redo
  uses retained states; eviction never jumps over missing edits to the original.
  Original comparison remains separately available. Applying after undo discards
  redo. Stale results are released without replacing the current image.
- Workers own frozen input copies, check cancellation and a five-second preview
  deadline. Source disposal cancels work and releases only owned history/results.
- Canvas supports fit, actual 100%, zoom/pan, pixel rulers, bounded high-zoom grid
  and unfiltered high-zoom pixels. Selections clear when source/history changes.
  A thin fitted image is drawn with at least one display pixel rather than a
  zero-size draw. This does not resize its output bitmap.
- Pixel/gray template tests and OCR tests consume the displayed bitmap through
  the existing bounded runtime recognition-preview API. They do not recapture
  the screen, substitute algorithms or generate touch coordinates. Match result
  markers are UI overlays only. Parameter changes require a new test.
- Saving reuses folder creation, explicit name, original-pixel PNG import and
  non-overwrite naming. Save workers own independent copies; no script is run or
  block inserted. Closing unsaved processing asks for confirmation; a completed
  save is tracked for that current image.
- Removed the image library's separate mutable per-pixel gray worker and one-way
  history. Library capture/load now has an epoch/cancellation owner; capture's
  displayed three-second switching delay is real rather than only descriptive.

## Verification

The new Kotlin tests cover rotation/mirror pixel order, nearest sampling,
half-open ROI, integer gray/alpha preservation, source immutability, cancellation,
history eviction/redo/stale completion and memory-overflow rejection, ruler bounds
and the distinction between fit and actual size.

`runtime-executor` additionally runs the same golden rotation/mirror/resize/gray
vectors through the actual Rust implementation. This verifies CPU pixel semantics;
it does not prove Android Bitmap premultiplication, rendered gestures or device
recognition acceptance.

`scripts/check-rust.ps1 -Offline` passed formatting, strict Clippy, workspace
tests, schema validation and generated-contract comparison. Runtime-executor now
has 95 cases. Existing Windows incremental-cache hard-link warnings remain.

Final `build.cmd -Verify`: successful in 2m14s, 1075 actionable tasks. Studio's
659 cases have 0 failures/errors and 1 skip. Lint has 0 errors, 36 warnings,
16 hints. APK/native/model/layout checks passed; MuMu 0 update installation
succeeded with existing app data retained. No private script was executed.

Final development/full APK: 175,990,698 bytes. Compressed ZIP payload:
172,294,705 bytes; remaining layout/signing/padding: 3,695,993 bytes. These are
package measurements, not inference performance or optional-provider savings.

## Remaining requirements

- Direct reversible transform-pipeline insertion is **not implemented**. The
  workbench deliberately cannot backfill processed-image coordinates into a raw
  screen capture or touch block. Existing raw screenshot insertion is separate.
- Android rendered/layout/gesture, source-switch and save/cancel lifecycle
  acceptance; representative Android Bitmap alpha behavior on API24/25.
- Actual optional-provider import/selection/service loading/availability and
  synthetic OpenCV/ONNX device acceptance; default Studio stays full.
- Missing Rust processing additions require concrete semantics and contracts;
  this preview increment does not claim those APIs have been added.
- Real condition-separated performance/memory observations and release regression
  remain in the long-term execution plan. No downloader or arbitrary filter code.
