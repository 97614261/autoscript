# Processed-frame recognition tails: 2026-10-14

## Implemented

[ADR0103](../adr/0103-processed-frame-recognition-tails.md) extends the existing
workbench rather than adding another image registry or runtime API.

- Pixel and gray match tests can pass their exact configuration to “加入处理 +
  找图”. OCR tests can pass glyph or alphanumeric settings to “加入处理 + 取字”.
  Library workbenches outside an editor still only test/save.
- One bounded proof binds source object identity, algorithm, complete options,
  resource and accepted runtime reply. Changed parameters discard it; acceptance
  rechecks runtime generation. Cancelled/failed/mismatched replies cannot enable
  insertion. Not-found is a valid tested configuration, not a success claim.
- The insertion sheet requires distinct accessible declared output variables of
  matching types. No hidden declarations or variable values are logged. It uses
  ADR0102's full combined-draft validation, position selection and one undo.
- Recognition consumes the final processed image. Pixel/gray/alphanumeric use
  autoCapture=false, glyph borrows the supplied frame; no automatic touch action
  is generated. The final image is released by default, or explicitly retained
  for later operations in the same task with its release responsibility shown.
- Match template selection is now a compact searchable list. Output settings are
  bounded/scrollable instead of expanding the whole template inventory inline.
- Fixed glyph foregroundRgb from a hexadecimal string to the existing integer
  schema contract. Canonical glyphDictionary resources are recognized, with the
  existing glyph_dictionary read alias retained; manifests are not rewritten.

## Verification

- New Kotlin tests cover four tails, exact ROI/options, frame usage, default
  release/explicit retention, typed and unique outputs, missing resources,
  changed dimensions, invalid single-line OCR bounds and canonical dictionary
  recognition. Proof tests cover source identity, copied options, changed
  resource/algorithm/runtime, failed/cancelled replies and operation mismatch.
- Targeted Kotlin tests passed. Full Studio gate: 674 tests, 0 failures,
  0 errors, 1 existing skip.
- A real Rust compiler/runtime synthetic test executes registered source -> gray
  -> clockwise rotation -> crop -> pixel matching -> final release. It reports
  found=1, x=0, y=0, score=1000; remaining task resource count is zero. Generated
  Lua has no Screen.capture() or Input.tapScreen call. Additional compiler tests
  cover gray/alphanumeric/glyph borrowed-frame tails and glyph dictionary cleanup.
- `scripts/check-rust.ps1 -Offline` passed formatting, strict Clippy, all workspace
  tests, schema validation and generated-contract comparison. Flow compiler now
  has 118 tests; catalog remains 175 blocks/159 runtime APIs, 185 schemas/522 JSON
  documents. An initial strict-Clippy literal-format warning in the new test was
  corrected before the final successful gate.
- `build.cmd -Verify` passed in 1m47s, 1075 tasks. Lint: 0 errors, 36 warnings,
  16 hints. APK layout/ABI/model checks passed and MuMu 0 update installation
  succeeded, retaining app data. APK 178,334,244 bytes; compressed entry payload
  172,359,403; ZIP layout/signing/padding 5,974,841. These are package observations,
  not optional-provider savings or recognition performance measurements.

## Still unproven

- Android rendered UI/gestures, alpha handling, source/orientation changes and
  cancel/stop lifecycle acceptance; the host bitmap semantics tests do not prove
  Android premultiplied-alpha equivalence for every image.
- Real OpenCV/ONNX/glyph device tests of the new workbench workflow, standalone
  exports and supported ABI/API24/25 matrix. No private script was executed.
- Provider import/selection/actual runtime loading and truthful runtime
  availability still require production integration. Studio remains full;
  provider builds/storage tests do not mean runtime on-demand delivery is done.
- Condition-separated P50/P95/P99 and peak-memory evidence remain pending.
  The overarching plan stays active; no downloader or algorithm substitution.
