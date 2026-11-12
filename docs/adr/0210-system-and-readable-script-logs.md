# ADR 0210: System logs and readable script activity

## Request

Expose only two log categories: system state/errors/crashes with plugin and visual-row context, and chronological readable script actions with coordinates, search regions and actual outcomes. This request is local implementation only; it does not authorize a new release, installation or publication.

## Implementation

- The editor log dialog exposes System and Script Activity, with one selector for current records and project-scoped previous runs. Remove the old statistics, comparisons, raw-observation and recording-settings UI. Retain search, follow, readable copy/export, bounded history deletion and trusted block navigation.
- Record touch and vision actions from the executor's real arguments and typed completion, independently of optional trace/vision metrics. Pending clicks do not claim completion; rejection, timeout and cancellation retain distinct states. A normal miss or zero color count is not an execution error. Existing scheduling, Root, input arbitration and result semantics are unchanged.
- Attach the plugin name, visual row, block title and structural identity from the executed checkpoint, rather than looking them up from a later edited project. Other executed rows supply readable step records. Preserve repeated actions and explicit script output; internal node IDs remain navigation metadata and are stripped from normal display/export.
- Cover click/swipe/key/pointer actions, capture, ordinary/multiple/gray/scaled/feature image matching, single/multiple/color-count operations and OCR/glyph recognition. Show bounded OCR text and result counts instead of dumping result JSON. Polygon records identify the actual polygon marker/native region.
- Preserve the existing per-run bounded writer, ownership locks, rotation and explicit loss reporting. Legacy trace, metrics and event codecs remain internal compatibility data; they are not additional visible log categories.
- Persist runtime state and full bounded failures in a small process-shared private system journal. Install a chained Java/Kotlin uncaught-exception handler in Studio processes; it saves the exception and last known execution position before delegating to Android's original handler. Android 11+ also imports historical crash/native-crash/ANR exit reasons after restart. Earlier Android versions do not have that historical exit API. Last known execution position is labeled as such, not presented as proof that the last block caused a crash.
- System journal files are capped at 256 KiB each with one previous segment and bounded cross-process lock wait. Script actions continue through the asynchronous bounded run writer rather than synchronous per-action filesystem writes.

## Verification

The initial Rust action-test helper used nonexistent virtual-host constructor names; compilation failed before tests and the fixture was corrected. New action events also invalidated five old assumptions about the total count/order of mixed telemetry records. Update those assertions to select their original event kind and explicitly check the new independent action/step records; do not relax metric gating, redaction, bounds, error context or loss accounting.

Twenty scoped Rust checks then passed. After adding polygon-marker and detailed-glyph regressions, all seven action checks passed. These include actual temporary Lua execution against the public virtual host, pending/failed dispatch, timeout, misses/count zero, OCR text versus JSON and frozen execution location. No device gestures or user projects are involved.

Android's first attempt was blocked by the external Gradle cache sandbox, and the second by PowerShell property tokenization. Both failures are retained. With existing offline cache access and quoted properties, Release production/test source compilation and 79 scoped JVM checks passed. Final incremental production/test compilation and eight new-category/journal checks passed; including the additional real-journal roundtrip, this covers 80 distinct Android cases. Preserve the first XML results in `build/log-redesign-first-results/` and logs `build/log-redesign-android-*.log`, `build/log-redesign-rust-*.log`. Targeted diff whitespace checks passed.

The crash check invokes the installed handler with a temporary exception and verifies persistence/delegation; it is not an actual device crash. No device UI, native-crash/ANR or ARM acceptance is claimed. No Studio delivery APK, version change, device installation/connection, private-script execution, online publication, Git commit, backend deployment or server/key/project-backup change is part of this request. Gradle's existing source/test dependencies may regenerate the internal Runner template. Online remains the existing 0.1.40 ARM package.
