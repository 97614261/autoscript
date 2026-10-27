# ADR 0156: Studio second-batch lifecycle closeout

Status: accepted, 2026-10-24.

The user requested the previously agreed second batch, items 6-11: opening and
switching cost, resource ownership, failure recovery, constrained layouts,
draft/save safety, and update/package cleanup. This is a directed audit and
necessary repair, not another feature or performance programme. Do not redesign
the editor sidebar or bottom toolbar. Items 12-13 (centralized acceptance and
packaging/delivery) remain deferred. Run only the final Studio Kotlin compilation
target, not tests, devices, packaging, installation or publication.

Thumbnail decoding owns its result through cancellation and row disposal. Library
source previews are released when their workbench leaves composition, after its
borrowers are disposed. Do not scale saved resources or add an unbounded cache.

History logs retain at most one selected-run event snapshot per dialog. Parse
statistics and failure locations once per explicit refresh/run selection; channel
switches read only their bounded text. Returning from live to history refreshes
the snapshot. Filtering large retained text is cancellable background work, with
no stale result displayed under a new filter. Controls may wrap/grow and scroll
within a bounded portion of short windows; the log remains independently usable.

Draft checkpoint strings are frozen after debounce on the composition thread;
hashing/serialization and disk operations run off it. Discard has the same
session/revision ownership as writing, so a disposed session cannot delete a
new session's record. Saving a subset validates both its formal resulting project
and, when different, the combined open drafts; it does not silently save others.
Existing atomic transactions and exact stale-source checks remain authoritative.

Project operations propagate cancellation and release busy state in finally;
duplicate operations are rejected before launching. Capture-preview copying has
cancellation checkpoints and verifies the exact service/generation before
publishing. Cancellation deletes only that request's private temporary image;
disconnect never replays input or substitutes a cached screenshot.

Updater disposal invalidates its epoch and pending installer request. Cancellation
must also purge the updater's own cancelled queued work so an immediate retry
does not fail merely because its queue slot remains occupied. Keep one bounded
worker, same-origin download, size/digest/certificate/package verification,
explicit system installation and owned-file-only cleanup. No downloader for
models/components, default-model change, protocol expansion or user-data deletion.

Device packaging always releases its owned/global busy identity even if staging
path verification or cleanup throws. Existing bounded snapshot, signing,
cancelled-result cleanup and private staging boundaries are retained. No broad
dead-code deletion is justified by this audit; compatibility and tested helpers
remain. Remove only redundant statements introduced by these targeted repairs.

Compilation: Studio compileDebugKotlin with the development ABI profile passed
on 2026-10-24 in one invocation. No tests, unit-test compilation, device actions,
APK packaging, installation or publication ran. Layout interaction, long-run
memory/performance and fault recovery have not been device-accepted for this
candidate; historical results must not be attributed to these changes.
