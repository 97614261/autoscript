# Edit-tree folding and image insertion positions, 2026-10-12

## Changes

- The edit-operation list and main program tree share the same expansion state.
  The edit list renders only visible nodes and adds a separate expand/collapse
  arrow beside the checkbox. Nested collapse flags survive reopening parents.
  Selection location uses visible indices; a hidden selected row expands only
  its collapsed ancestors. Folding does not alter nodes or executable arguments.
- Right-side image configuration and recognition preview/OCR insertion now freeze
  their configured block batch and open a position picker. Supported positions:
  before, after, actual child branch and root-list bottom. Multiple branches require
  explicit selection. No replacement option is exposed for a multi-block batch.
- Cancel returns to retained configuration/preview state without inserting. An
  existing function-library insertion request keeps its explicit position and
  is not prompted again.
- Position confirmation rechecks project/flow/source/selection and required
  capabilities; each host additionally gates busy/read-only/runtime state.
  Insert uses existing atomic insertBlocks, keeping capture/match/release order
  and a single undo operation. Failed or stale requests cannot silently target a
  different row or plugin.
- Both VisualProjectScreen and ProjectPage's floating fallback use the shared
  picker and request. The fallback also reuses the existing image-entry sequence
  builder so result reads do not gain unrelated capture/release blocks.

## Tests and limitations

New tests cover nested visibility, retained inner collapse flags, selected
ancestor location, shared arrow wiring, all positions and actual branch depth,
batch order/undo, stale contexts, capabilities, bounded batches and frozen values.
No Rust contracts, persisted layouts or input/vision algorithms were changed.

Validation: final `build.cmd -Verify` passed. Studio has 595 tests, zero failures
or errors, including 3 folding and 4 image insertion tests. Lint remains at
0 errors, 32 warnings and 17 hints. Debug APK installed on MuMu 0 at
127.0.0.1:16384 preserving app data. Repository layout and whitespace checks passed.
The request uses a restricted ordinary class, without an exposed data-class copy
constructor; final compilation adds no new warnings beyond existing deprecations.
Device visual acceptance and private-script execution are not part of this check.
