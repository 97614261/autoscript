# ADR 0220: Save image-processing results directly

## Request

The filter editor appears to lose image saving. Check the current image tools
and repair related save paths. No installation or publication is requested.

## Findings and Decisions

- The filter editor's old Save button persisted only filter presets. Rename it
  to `存方案` and provide `应用并存图` for actual bitmap processing. Mask, cleanup
  and threshold editors offer the same operation; argument-only editors keep
  their existing Apply action and do not offer an image save without pixels.
- Keep Apply separate for consecutive edits. Apply-and-save captures the current
  selection before asynchronous processing and opens naming only after a
  successful, non-stale history publication in the same workspace. Cancellation,
  processing failure and stale results cannot queue a later unrelated save.
- Bind each save request to its published image, pipeline, rectangle and copied
  polygon coordinates. Reuse existing PNG import, overwrite/numbered choices,
  original capture transactions and resource conflict checks. Canceling naming
  keeps the processed preview and its undo history.
- Enable the fixed toolbar save with no rectangle: save the entire displayed
  image. Valid selections save their exact region and polygon transparency.
  An incomplete, crossing or stale polygon cannot silently become a rectangle
  or full-image save. Explicit Save Whole Image always ignores the selection,
  including a polygon whose bounding box equals the full image.
- Screenshot color/filter/cleanup edits preserve pixel positions just as mask
  edits do. Retain the original-screen location hint through those edits and
  crops. The hint describes coordinates, not unchanged colors or a matching
  guarantee. Resize, rotation and reflection still omit it. Do not invent a
  screen origin for library images without an existing capture context.
- Disable insertion of a processed pipeline while comparing its original image.
  Preserve processing budgets, source ownership, preview cancellation, alpha,
  navigation/discard guards and existing matching/Root behavior.

## Verification

Forty-seven focused JVM checks pass: toolbar save targets 11, PNG metadata and
alpha 11, recognition/crop provenance 5, history rejection 3, pixel processing
6, advanced pixel/mask processing 5 and navigation 6. Six checks are new.
The filter test runs actual pixel filtering and a lossless PNG metadata
round-trip, verifies transparent/partial-alpha pixels, and preserves its source.
Existing stale/closed history and invalid/canceled pixel checks are included;
these do not simulate tapping the new footer on a device.

Debug production/test compilation, Debug assembly and full Debug lint pass
in one concentrated run (zero lint errors; 49 warnings and 9 hints remain).
Evidence: `build/image-save-audit-checks.log` and the corresponding profile's
JUnit XML/lint reports. Targeted diff whitespace checking passes. Document
source changes are not deployed.
Host checks do not constitute device visual, touch, keyboard or ARM acceptance.
No device connection, installation, version bump, online publication, Git
commit, private-script execution, server/key/backup or Root changes are made.
