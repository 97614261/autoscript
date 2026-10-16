# ADR 0103: Tested recognition tails on processing pipelines

Date: 2026-10-14. Status: accepted implementation increment of ADR0102.

## Decision

A successful/not-found preview may pass its exact immutable pixel/gray matching
or glyph/alphanumeric OCR configuration and processed-frame ROI to the insertion
sheet. Changed parameters invalidate the result; current runtime generation is
rechecked before accepting it. Source/history changes discard this configuration.
No recognition success is inferred from a previous frame or different settings.

Insertion explicitly selects accessible declared outputs of the correct scalar
types, distinct from each other and both image slots. Append the existing matching
or OCR block to the exact processing sequence, always using its final frame with
autoCapture=false and no success click action. Release the final frame after the
recognition tail by default; explicit retention keeps its same-task ownership and
shows the user's release responsibility. Compiler/position/stale-draft checks and
one-undo atomic application continue through ADR0102's shared path.

Processed coordinates are not device touch coordinates, even when the dimensions
happen to match. No inverse mapping, algorithm substitution, hidden variables or
extra screenshot is generated. The workbench can test/save outside an editor;
only editor-owned workspaces provide insertion. No new runtime API or SDK import.

## Gates

Host tests must cover all four tails, exact ROI/options, resource/type rejection,
disabled implicit capture/action, final release/retention and real Rust pixel
execution. Android UI, real OCR/OpenCV/provider and supported-device acceptance
remain separate from these tests and default Studio stays full.
