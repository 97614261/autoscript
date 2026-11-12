# ADR 0217: Inline image toolbar and direct polygon save

## Request

The user could not discover saving after selecting a polygon, and requested
secondary toolbar menus instead of panels for simple image actions. No new
publication or version bump is requested in this batch.

## Changes

- Keep a fixed save-image action on the main toolbar in every selection mode.
  A polygon no longer requires switching to crop or navigating through the
  processing panel to save. Crop and region mode changes retain the shape.
- Use one horizontally scrollable secondary strip for mode, selection, view,
  test entry points or processing actions. Measure both rows as one draggable
  bounded toolbar; keep save, confirm and close outside the scrolling area.
- Rectangle and polygon choices directly change the full-size canvas. Reuse
  the original vertex gesture implementation for add/edit, whole-shape move,
  image pan and pinch; incomplete shapes remain editable. Vertex history has
  33 states and 128 vertices, with gesture cancellation and pinch kept out of
  edit history. Reset history for external/source changes.
- Validate the same shape for saving and emitting: fewer than three vertices,
  crossings, invalid bounds and stale polygon rectangles are rejected with
  visible guidance. Cache this geometry check by source/shape, independent of
  viewport changes.
- Rotate, mirror, gray, crop, transparency and view actions execute directly.
  Keep the existing parameter/preview editors for filters, threshold, resizing,
  mask cleanup, OCR, glyphs, recipes and coordinate detail. Naming/overwrite,
  insertion and discard confirmations retain their original guards.
- Preserve original-pixel save/mask/provenance, budgets, cancellation,
  overwrite transactions, recognition proof and insertion checks. No change
  to screenshot capture, Root, algorithms or runtime matching semantics.
- Remove the replaced mode-grid and separate zoom-control UI and unused
  workspace menu panels/callbacks.

## Verification

Seven geometry/history/action-regression checks and six existing guarded
navigation checks passed. Debug production/test compilation and local QA APK
build passed. Preserve the initial missing isolated-cache argument log.

The first actual MuMu run drew three vertices on the unchanged full canvas,
but the acceptance helper could not classify the undo control as clickable.
The history cursor and history list were made observable so button enablement
subscribes to their changes. A subsequent screenshot still showed the helper
timeout while displaying an enabled undo control. The fixture now taps the
observed control bounds and requires a real decrease/increase of vertex count,
rather than relying on its accessibility ancestor classification. Both failed
runs cleaned their owned QA projects and their logs remain. The screenshot
also exposed unnecessary full-screen toolbar width; the secondary strip is now
bounded by the measured primary row, with separate save/confirm spacing.
The final source-consistent candidate passed four actual MuMu cases: open the
selection strip without reducing the canvas, draw three vertices and undo/redo
them, save the polygon directly with correct transparent pixels and original
frame/region metadata, run and undo gray processing without a panel, and keep
save/close visible in portrait. The four cases group these related actions;
they are not additional independent cases. Landscape and portrait screenshots
were inspected. The final run cleaned its owned QA project and restored the
orientation; Sogou remains the only enabled/default IME, with rotation settings
1/0 unchanged. Evidence:
`build/function-usage-imageToolbar-20261109T150520.log` and
`build/image-toolbar-secondary-device-final.log`; the source-consistent build
is `build/image-toolbar-secondary-source-sync.log`.

MuMu retains the data-preserving local Studio 0.1.44/code45 Debug x86_64
candidate. These synthetic-pixel toolbar checks do not claim new Root capture,
ARM/game matching or keyboard-occlusion acceptance. No version bump or public
package upload was requested or performed.

Evidence: `build/image-toolbar-secondary-*` and
`build/function-usage-imageToolbar-*`. The public ARM64 baseline remains
Studio 0.1.44/code45. No private project scripts are run or Root/server/key/
project-backup settings changed; no Git commit is included.

The later explicit publication request includes this change in Studio
0.1.45/code46 ARM64. See the publication authorization and completed delivery
results in ADR 0218; the existing checks above were reused.
