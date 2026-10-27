# ADR 0152: Compact visual-tree navigation and insertion previews

Status: accepted and implemented, 2026-10-24.

## Decision

Implement the user's six editor improvements without changing project data or
execution semantics. A repeated tap on the sole selected node toggles its full
structured display summary; aliases remain opaque and parameter hiding remains
respected. Only one summary is expanded per list. Long press still opens editing.

Conditional, multi-image and plugin-call arms share view-only branch headings.
Headings are exact insertion anchors, never node identities for execution,
clipboard, deletion or range selection. Existing draft/selection checks remain.
Conditional headings are ordered then, numeric else-if, else; unknown nodes
remain visible. Loop bodies need no extra heading.

Insertion choosers preview the real sibling/subtree boundary and explicit child
slot, including empty slots. An unresolved multi-slot destination asks for an
explicit branch, never previews an invented first branch. Previewing does not
mutate selection or drafts. A collapsed container shows its descendant count.

Range selection uses the visible node order, excludes headings and commits once;
over 256 nodes is rejected without partially changing selection. Selected
containers retain the existing subtree-operation semantics. Manual tree scrolling
detaches execution following, including automatic ancestor expansion. A compact
return-to-running-row action restores following. Programmatic scrolling must not
detach itself. These are UI-only states, not project or runtime state.

## Verification

The five focused JVM test classes cover 56 cases, including actual condition and
multi-image branch insertion/undo, subtree boundaries, empty slots, the atomic
range limit and literal aliases. The development build installs on MuMu 0 with
existing data retained. This is not a new phone or full visual acceptance run;
no online publication is included.
