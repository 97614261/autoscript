# ADR 0234: Touch picker markers and polygon template defaults

## Scope

The user deferred the ordinary matching threshold/tolerance changes, reported
polygon-template misses, and clarified that the touch picker had lost its two
swipe/drag points and looked like the image toolbar. No release, publication,
device installation, private-script run, or Git commit was requested.

## Touch picker

- Pass the entry purpose explicitly from the visual editor. Touch picking uses
  the original point canvas directly, without entering the image-processing
  workspace merely because a project, bitmap, and runtime client are present.
- Keep the compact toolbar and drag position. The touch mode menu contains tap,
  long press, swipe, drag, pointer down/move/up, and the existing recording entry.
  Hide image selection/save actions there; keep screenshot, zoom, pixel adjustment,
  duration, confirmation, and close. Image entries keep the image workspace.
- Initialize both slide markers with each screenshot, before any mode-switch
  callback. Previously only switchMode initialized them, so direct swipe/drag
  entry started with null points and the canvas could not pick either marker.
- Reset restores both default points and exits image panning instead of leaving
  an unusable empty slide selection. Switching modes retains edited endpoints.
- The mode handle has an explicit button action and consumes its own initial
  press/release. Canceled, externally consumed, or multiple-pointer gestures do
  not open its menu. Input generation and runtime execution are unchanged.

## Polygon distinction

PNG crop polygons remain transparent template masks. The existing default search
range and template-range shortcut use the recorded outer rectangle, not that
polygon. Move the existing captured-template override clearing from the dialog
callback into recognitionCapturedTemplate itself, so the full handoff owns this
invariant. Do not alter matching algorithms, similarity, color tolerance, pixels,
manual search polygons, or persisted project nodes.

A synthetic masked triangle was passed to the current Rust matcher at its exact
original rectangle: it matched at 1000 permille. Adding that triangle as a search
polygon returned no match, because the search-polygon contract requires the
entire template rectangle to fit. This confirms the distinction; it does not
establish that a polygon is currently added by default or reproduce the user's
specific template/screen failure.

## Verification

One offline focused run passed 27 JVM checks: TouchToolRegressionTest (3),
ImageToolModesTest (12), and ImageRecognitionWorkflowTest (12). Production and
test Debug Kotlin compilation passed in the same 2m39s run. No broad suite or
full Lint was repeated. The unchanged dependency graph reused the internal
Runner template; no Studio delivery APK was built.

Evidence: build/touch-tool-regression-checks.log, the corresponding x86_64
profile test-result XML, and build/polygon-template-default-probe.log. The probe
links the existing host pixel-vision library; it is not an ARM, PNG decoding,
live-screen, or device UI/touch acceptance test. Targeted diff whitespace checks
passed. No device connection, install, input-method change, Root configuration,
server/key/project-backup change, or publication occurred. Public remains
Studio 0.1.51/code52 ARM64.

## Publication

The user subsequently authorized building and publishing this batch. Studio
0.1.52/code53 ARM64 was built with the existing development signature, full
vision profile, and V4 optimized distribution workflow. The existing 27 focused
checks were reused; no broad suite or full Lint was repeated. Release compilation,
compact packageRelease, signer/alignment/model/native inventory, and size gates
passed. Final APK: 36851938 bytes, SHA-256
c5fbf3c9267ef50ea61662e6db3f2f38475d841ee86f81d554cf931e94405522;
ZIP layout/signing/alignment overhead: 30919 bytes.

Publication used the existing mutually authenticated HMAC HTTP workflow. After
the signed preview, the replaced 0.1.51 package was moved out of public history
under the standing latest-only authorization, into recoverable private archive
archive-20261111T094505Z-2d985bf86d21172d. All eleven previous archive records
were retained unchanged. Public state contains one latest ARM64 package, no
history or alternatives; private archive inventory contains twelve records.

One public verification passed: no-store manifest bytes match the signed
revision, full APK size/hash/MIME, Range, checksum file, download-page HTTP 200,
and replaced APK/checksum HTTP 404. Final signed revision remained
0b6fda98aebd3a826da7eb438be9462172edadc61187f72edb5a3a512c2cadae.
Evidence: build/touch-picker-v052-*.

No new x86 build, device connection/installation, private-script execution,
Git commit, backend/docs-source deployment, Root/server/key/project-backup
configuration change occurred. Packaging and public checks do not establish
device mode switching, actual two-point dragging, or ARM polygon matching
acceptance; the user's live polygon-template miss remains unconfirmed.
