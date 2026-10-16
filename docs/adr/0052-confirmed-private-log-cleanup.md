# ADR 0052: Confirmed private run-log cleanup

Status: accepted, 2026-10-10.

Studio offers deletion of the selected run and cleanup of the current project's
completed history. Confirmation captures exact run IDs; a subsequent run is not
part of that request. A missing project identity never means all projects.
Cleanup is irreversible and does not affect projects, assets, exports or live UI
console data. No cleanup is performed merely by opening the dialog.

The writer holds a separate `writer.lock` file lease from the first disk flush
until the final queued records and metadata are drained, including across
processes. Cleanup acquires that lease non-blockingly and then the journal
snapshot lock, reloads project identity and terminal state, and preflights the
entire flat directory. Running records, busy writers, invalid metadata, unknown
files, subdirectories and redirected paths are preserved. Legacy running
records remain protected even after a crash; cleanup does not infer inactivity
from wall-clock age.

Only UUID directories directly inside private runtime-logs and exact known log
filenames can be removed. Partial IO failures are reported rather than presented
as success. Retention uses the same protection. Snapshot/export races fail
explicitly and cannot recreate a deleted run. Stop remains independent of disk IO.
