# ADR 0229: Thumbnails in image resource selection

## Decision

Replace text-only image resource choices with compact thumbnail rows. Show the
file name, optional parent folder and current selection. Single selection
commits on tap; multiple templates retain the existing ordered selection,
64-template limit, confirmation and discard protection.

Reuse the image library's existing asynchronous visible-row thumbnail decoder.
It validates declared local resources, limits decoded thumbnails to 128x128,
keys updates by resource revision and releases bitmaps when rows leave the
composition. Source files and recognition pixels are unchanged.

Use one searchable compact picker for the visual recognition single-template
entry, recognition-test source/template selection, workbench matching, loaded
image resources and feature matching. Multiple-template rows use the same
thumbnail layout. Keep the image picker isolated from the parent's workspace
registration; searches do not request initial keyboard focus. Other resource
types keep their existing selectors.

## Validation

Studio Debug production Kotlin compilation passed in 2m26s with the existing
x86_64 profile and absolute isolated Gradle cache. The initial relative cache
path was rejected before compilation; retained that log and corrected the
command. Evidence: `build/image-picker-thumbnail-compile.log` and
`build/image-picker-thumbnail-compile-initial.log`. Scoped diff and new-file
whitespace checks passed. No new tests were added for this reversible UI change;
no broad test/Lint rerun, Studio delivery APK, installation/device connection,
publication, Git commit, private script or server/key/backup change occurred.
Compilation does not establish device visual/touch/IME acceptance. The earlier
ADR0228 runtime fix remains local; online remains Studio 0.1.48/code49 ARM64.
