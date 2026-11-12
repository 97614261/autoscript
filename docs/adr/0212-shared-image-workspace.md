# ADR 0212: Share the screenshot canvas across image tools

## Request

The user approved consolidating screenshot selection, polygon selection, filters,
processing, recognition previews and glyph authoring into one compact workspace.
This batch changes local code only; it does not request a delivery APK, device
installation, online update or Git commit.

## Workspace

- Keep Clear outside the expandable Tools group. Clear targets the active
  polygon, mask, glyph sampling or OCR region draft when that editor owns it;
  otherwise it clears the screenshot's markers and selection.
- Put Rectangle and Polygon under Selection. The vertex editor borrows the main
  canvas and keeps its own vertex undo/redo, pan, pinch zoom and whole-shape move.
  Selecting a search region does not mutate image pixels. Crop Selection, Outside
  Transparent and Inside Transparent remain explicit processing operations.
- Route screenshots directly into the existing processing-history owner. Saved
  images use the same workspace in their existing dialog. Keep the draggable,
  width-bounded toolbar, with horizontal scrolling for overflow.
- Render parameters beside the canvas in landscape and below it in portrait.
  Filter, threshold, mask, cleanup, OCR, matching and glyph previews register a
  canvas with one guarded owner instead of opening another image window. Outside
  this workspace, the original parameter-dialog entry points remain usable.
- Retain filter presets, masks, cleanup modes, recipes, glyph authoring and
  recognition proof checks. Save/overwrite prompts, resource selection, discard
  confirmations and insertion-position transactions are still explicit.
- Suspend tool changes or rescreenshot actions until the current editor accepts
  closing. Keeping a draft cancels the pending navigation. Rescreenshot continues
  once after confirmed discard. Prevent the screenshot mode menu and final output
  action from interfering with an open parameter editor.

## Coordinates And Ownership

- Keep the original borrowed bitmap and bounded eight-edit/32 MiB history. Image
  workers still freeze their input, use the existing deadlines and cancellation,
  and reject stale results. No runtime screenshot cache or Root behavior changes.
- Use inverse pixel sampling for points/colors and inverse half-open boundaries
  for rectangular regions/polygon vertices. Crop, resize, quarter turns and
  mirrors map back through the full pipeline. ROI minima round down and maxima
  round up; invalid or degenerate regions are rejected.
- Sample screenshot colors from original pixels after inverse mapping, rather
  than taking a filtered black/white preview as the target color. Map selections
  once before emitting the existing typed visual selection.
- Restore a matching viewport when returning from a glyph material of different
  dimensions. Borrowed parameter canvases own their view transform while active.
- Preserve the screenshot window's original z-order and capture exclusion. Keep
  Back available while capture is pending. Raw screenshot saves use the original
  editor save callback and its frozen context. Processed-image saves reuse the
  same final template-backfill context guard after saving their actual pixels.
- Freeze save region, snapshot, callback and source bitmap before IO. A cropped
  save does not mark the entire edited image as saved. Only unprocessed screenshot
  saves retain raw screen-region metadata; processed files do not claim that
  metadata. Existing insertion proof/resource checks and frame release stay intact.
- Avoid mirroring workbench feedback into the image-tool system log a second time.
  Keep the two-category log routing introduced by ADR 0210/0211.

## Verification

The first and third Gradle attempts failed in sandbox-generated Gradle catalogue
classes before source compilation. The second explicitly supplied a vision
profile, which the existing distribution profile rejects. Reuse the installed
offline Gradle runtime with its required isolated project cache and default full
vision profile. The first actual source compile exposed state-delegate inference,
an omitted wrapper argument and an implicit layout receiver; these were corrected.
All initial logs remain under `build/image-workspace-compile-*.log`.

The focused JVM run passed 44 tests: 6 new boundary/selection mapping checks,
6 new guarded-navigation checks, 3 history, 6 processing pipeline, 5 polygon,
6 pixel-adjustment and 12 mode checks. There were no failed, errored or skipped
cases. Evidence is `build/image-workspace-checks-first.log` and the retained XML
under `build/image-workspace-checks-first-xml/`.

Subsequent UI/entry-point changes use incremental Release production/test source
compilation instead of rerunning these unchanged model tests. Relevant logs are
`build/image-workspace-compile-final.log`,
`build/image-workspace-compile-save-integration.log` and
`build/image-workspace-compile-template-backfill.log`. Whitespace checks cover
the changed image files, editor integration and guide. This does not establish
actual device rendering, touch gestures, keyboard behavior or ARM acceptance.

The local guide describes the new entry points. No documentation deployment,
Studio delivery package, version increment, installation/device connection,
private-script execution, online publication, Git commit, server configuration,
credential change or project-backup change is part of this batch. The known
online version remains the previously published 0.1.40 ARM64 package.
