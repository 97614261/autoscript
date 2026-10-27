# ADR 0130 Bounded image authoring and recognition tools

Date: 2026-10-19
Status: accepted

## Scope

Implement the user-approved eight image-tool stages: template masks, fixed-digit
cleanup, portable recognition recipes, stable recognition, explicit diagnostic
comparison, verified coordinate mapping, bounded multi-scale pixel matching,
and integrated Studio validation. No generic OCR, model replacement, download
service, plugin workflow library or independent Runner expansion is included.

## Immutable processing

Extend the existing Screen.transform contract, task-owned frame pool and Studio
processing pipeline. Mask and cleanup specifications are strict versioned data;
other transform modes reject unrelated options. Mask painting is expressed as
bounded original-pixel ignore/restore strokes and half-open rectangles, not
arbitrary code or a second frame registry. A gesture is one undo operation;
cancelled/multiple-pointer gestures do not publish. Restore affects only the
current mask edit. Saving writes a new PNG; the original resource is unchanged.

Masks have at most 64 strokes and 1024 sampled points, serialized at most 15,000
bytes inside the existing 16KiB transform options. Rasterization has a
64-million-work bound, shared cancellation/deadline checks,
and rejects an empty visible result. Cleanup is explicit binary processing:
small connected-component removal and radius1–2 erosion/dilation/open/close.
Foreground polarity and threshold are visible settings. Previews and execution
use identical integer semantics and cross-language golden tests; decimal points
and minus signs are explicit regression inputs, not automatically repaired.
The existing 4,194,304-pixel, 64-step and 32MiB preview-history budgets remain.

## Recipes

Project recipes copy processing operations, ROI and optional template/dictionary
settings. They do not include bitmaps, output variable identities, transient test
proofs or runtime results. Store versioned, bounded authoring metadata with strict
UTF-8, conflict-checked atomic updates and validated backup/restore. Loading a
recipe requires valid project resource references, matching geometry and explicit
confirmation before replacing an edited draft. Changed settings require a new
test before insertion. Runtime blocks embed copied settings and never consult a
mutable recipe file. This is not the postponed plugin workflow template library.
Limits: 32 recipes, 32KiB each, 1MiB total in `.studio/image-recipes.json`.

## Runtime and geometry

New pure frame queries use the existing bounded executor CPU path, declared
capabilities and cooperative cancellation/deadline checkpoints, as Screen.transform
does. They do not perform blocking host I/O or create another worker/VM. Region
comparison reports measured changed-pixel counts/ratios;
stability and repeated-hit workflows expand to visible blocks, use declared work
variables and real fresh captures, release intermediate frames and share a total
monotonic deadline. A repeated cached frame cannot prove stability.

Coordinate conversion requires a captured-frame origin and a complete bounded
crop/orthogonal-rotation/mirror/nearest-resize lineage. File-loaded images have no
screen origin. Keep source/processed/screen coordinates separate and expose an
explicit mapped-position preview before any action. Mapping is computation,
never an implicit click or a claim that the screen has not changed.

Multi-scale pixel matching is a separate opt-in operation with explicit minimum,
maximum and step, one aggregate work budget and a bounded scale count. It reports
the actual scale and original frame position, preserves alpha-mask semantics and
does not silently select OpenCV, resample user files or alter existing searches.

## Diagnostics and verification

Diagnostics are explicit original/template comparisons with an identified
candidate position, visible mismatch overlay, geometry checks and labelled local
analysis timing. They do not claim an exhaustive nearest match unless performed,
save private images automatically, or replace runtime performance measurements.

Each stage connects runtime contract, compiler, reversible source, Studio
configuration/history, tests and documentation. Final acceptance includes strict
schema/API checks, Kotlin/Rust tests, lint, APK checks and owned MuMu synthetic
workflows. Real-device/complex-background accuracy and performance percentiles
remain separate from compilation and synthetic acceptance.
