# ADR 0059: Confirmed editor draft recovery

## Decision

Studio stores recovery checkpoints in its private `editor-drafts` directory,
outside projects and backups. A checkpoint contains only committed editor IR
drafts, never unconfirmed parameter-dialog text or generated Lua. Writes are
debounced on IO and bounded to 16 plugins, 1 MiB per plugin and 20 MiB per project.
Each entry records its plugin/root identity and SHA-256 of the committed source;
the checkpoint also records the complete manifest digest. Incompatible state is
not automatically applied or promoted to project source.

On reopen the user may recover one plugin at a time or explicitly discard the
checkpoint. Recovery reuses compiler validation and stale-context checks and is
an undoable editor mutation, not a disk save. Failed validation retains the
checkpoint. Valid saves replace or remove the checkpoint. Interrupted sidecar
commits may use the known previous sidecar, never project tmp/bak files.

## Limits

An abrupt process kill before the debounced checkpoint finishes may lose the
latest edits. Checkpoint errors are visible and do not interrupt editing or
claim the formal save succeeded. No device acceptance is implied by host tests.
