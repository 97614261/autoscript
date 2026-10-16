# ADR 0086: Continuous editing and paste before the selected row

Date: 2026-10-12
Status: accepted by user's latest request

The bottom Edit window stays open across mutations and automatic save/validation.
Busy disables commands, not the window. Detail dialogs layer above the retained
list. Running, context changes or explicit dismissal still clear the window.
Existing stale-draft, rollback and confirmation guards remain.

Paste offers before/after/child locations, with "before this row" first. Before
inserts the copied forest before the first selected root, preserving
clipboard order, parent/block membership, subtree identities and cross references.
No selection appends to the root list. Copies remain selected with their first
root primary, so repeated paste cannot interleave forests. Each paste is one undo.
After/child locations remain unchanged. Low-level subtree paste keeps its
historical after-node default.

Indent/outdent remain in the flat 18-action grid. The list
indents checkbox and label together with guides and bounded horizontal scrolling.
These display changes do not modify stored depth or plugin data.
