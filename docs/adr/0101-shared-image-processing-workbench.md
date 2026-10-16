# ADR 0101: Shared image processing workbench

Date: 2026-10-13. Status: accepted implementation increment.

## Decision

The image library and screenshot canvas open the same bounded processing
workbench. The original bitmap is borrowed, never modified or recycled by its
history. Processed results are owned independently; at most eight retained edits
and 32 MiB including the original are kept. Applying after undo discards redo;
eviction bounds undo to the retained timeline rather than skipping missing edits
back to the original. Original comparison remains independently available.
Failed, cancelled or stale work does not replace the current image. Explicit
dimension/pixel limits reject work instead of silently downsampling.

Editor previews follow the existing `Screen.transform` modes: integer weighted
gray with preserved alpha, clockwise right-angle rotations, axis mirrors, and
explicit nearest-neighbor resize. Crop follows the existing half-open pixel ROI.
Preview computation is SDK-independent Kotlin, not a new script API or a second
runtime frame registry. Golden pixel fixtures cover the existing Rust semantics;
script execution continues to use Rust through the existing contracts. No UI
dependency on JNI, OpenCV or ONNX is introduced.

Source changes, processing, history navigation and original comparison clear
stale selections/results. Test/save workers hold independent immutable copies,
use cancellation checkpoints and bounded deadlines, and reject stale completion.
Overlays never become output pixels. Saving explicitly publishes a new image
resource through the existing folder/name/import flow; it does not overwrite the
original, add blocks or run a script.

Processed-image test coordinates belong to that image, not to the device screen.
Do not directly backfill them as touch coordinates or auto-captured recognition
coordinates. Reversible insertion of the explicit transform pipeline is still
a separate stage-8 requirement; this increment does not claim that stage complete.

## Scope

No downloader, arbitrary filter code, silent algorithm substitution or extra
runtime capability is added. The current optional-provider/full-profile gates
remain unchanged. Android rendered/device and transform-pipeline insertion
acceptance must be recorded separately from pixel/history unit tests.
