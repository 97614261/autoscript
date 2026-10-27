# ADR 0155: Complete the five editor workflow improvements

Status: accepted, 2026-10-24.

The user approved all five items as one coding batch, with no repeated testing.
Do not run tests, device acceptance, packaging, installation or publication.
Finish with the Kotlin compilation target only. Repair compiler errors and
recompile that target if needed; do not expand into tests or device acceptance.

Project search retains session-local filters, position and active result. Group
results by plugin and expose actual matching parameters and disabled-subtree
state. Navigation uses exact frozen source identities; stale results require an
explicit refresh. View navigation never saves, runs, or rewrites blocks.

Replacement is typed, bounded and preview-first: literal text uses a conservative
field allowlist and skips variable bindings; images use declared image-resource
properties and explicit project resource selection. Never replace raw JSON,
identities, variables, expressions or directory references. Validate all drafts
then recheck manifest, formal sources, all drafts, selection and runtime before a
non-suspending atomic draft commit. Failures restore exact editor/history state.

Cross-plugin edits tag existing local undo entries with one session-owned group.
Undo/redo intercept the group in every participant, refusing if any participant
has later changes or no longer has the exact history boundary. Do not perform a
partial undo, discard unrelated history, or automatically save only one member.
Groups and retained source snapshots are bounded and never enter project backups.

Batch parameters add explicitly safe boolean/enum fields and multiple selected
fields, keeping literal numeric limits, variable-binding protection, explicit
row selection, full-draft validation and one local undo.

Known compiler/runtime codes get concise advice and copyable original details.
Do not infer locations or parameter paths from free-form Lua/diagnostic text.
Exact parameter focus requires structured metadata; otherwise open the existing
parameter editor without guessing a field. No protocol expansion in this batch.

New panels use compact headers, fixed operations and independently scrolling
content. Close with uncommitted input asks before discard; cancellation closes
owned jobs without applying late results. Sidebar and bottom-bar design, project
format, execution semantics, full vision and models remain unchanged.

Implementation notes: previews reject growth beyond 1Mi text characters per
literal, 1024 entries per image-list field, or 32MiB of combined parameter data
before retaining a preview. At most 64 live multi-plugin history groups are
retained; live boundaries are never silently evicted to admit another group.
Search filters collapse and scroll within the available height; result lists
and operation areas scroll independently. Only structured compiler identities
enable navigation, and the original parameter editor remains authoritative.

Compilation: Studio compileDebugKotlin (development ABI profile) passed on
2026-10-24. The first attempt reported a layout-scope opt-in and a field-key
lookup error; both were repaired and the same target recompiled successfully.
No tests, unit-test compilation, device actions, packaging or publication ran.
