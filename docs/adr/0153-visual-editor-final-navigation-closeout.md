# ADR 0153: Visual editor navigation closeout

Status: accepted, 2026-10-24.

## Scope

Implement the user's final five editor improvements. Preserve script execution,
project identities, draft checks, insertion semantics and existing toolbar style.
This batch builds and installs MuMu 0 only; no online publication or phone work.

## Decisions

Cache only one immutable row projection and one display projection per editor,
keyed by exact source and a copied plugin-name map. Selection and save markers
are not content changes. Do not cache or share mutable editable JSON documents.
Source changes, undo/redo and plugin renames invalidate the affected projections.
Variable candidates are collected from one detached parse, then remembered by
source, declarations and plugin identity; selecting a row does not repeat that
scan. Step availability uses the same projected own/inherited disabled flags.

Execution following belongs to the project screen, not the current plugin.
Manual detachment gates both scrolling and automatic plugin changes, including
paused debug positions. Returning follows the latest validated position and exits
an incompatible focused view. No runtime delays or execution changes are added.

The edit list scrolls only when its selection is outside the visible viewport;
below-viewport selections align near the bottom rather than always at the top.
Expand/collapse must not move an already-visible selection.

Container focus is a temporary display projection with rebased indentation and
clickable ancestor breadcrumbs. It never mutates depth, nodes, selection or
insertion anchors. Full-tree identities remain authoritative for all operations,
counts and stale checks. Removing a focused node safely restores the whole tree.

Selection impact counts the union of selected subtrees once, including folded
and out-of-focus descendants, and excludes branch headings. Show selected rows
separately from actual affected rows; outside-focus selections remain explicit.
These UI states do not enter project backups or generated scripts.

## Verification

The targeted JVM run passed 123 tests in 10 classes with no failures or skips:
projection caching, focus/navigation, branch anchors, folding, compact labels,
continuous editing, display settings and existing editor mutation/history cases.
The first run caught an incorrect test fixture field (`targetFlowId` is required)
and an old prompt-description assertion; both expectations were corrected to
the existing contracts before rerunning. Ordinary `build.cmd` then succeeded and
installed the development APK on MuMu 0 (`127.0.0.1:16384`) with existing app data
retained. APK size: 100,845,401 bytes; dual ABI and default full vision unchanged.
No full Verify/Lint, phone tests, new device interaction acceptance or online
publication are claimed for this batch.
