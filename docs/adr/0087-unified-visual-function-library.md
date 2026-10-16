# ADR 0087: Unified visual function library and read-only API reference

- Date: 2026-10-12
- Status: Accepted

## Context

Studio only edits visual projects. Both editor dock callers supply visual program
nodes, and the historical Lua project policy blocks editing. The separate Lua
function catalogue, syntax templates and insertion examples have no supported
production entry. Generated API documentation remains useful and must not be
removed together with that obsolete UI branch.

## Decision

- All editor library entry points use the generated visual block catalogue.
- Remove the old Lua catalogue, example/default generator and syntax templates.
- Keep all generated API contracts as a read-only reference in function documents;
  API reference entries have no insertion snippet and cannot be inserted.
- Classify blocks and API references consistently by purpose. Separate text,
  collection/JSON, time, audio and floating tools from oversized mixed categories.
- Use compact subsection headings for large categories, stable ordering and API
  aliases for visual search. Insertion continues through existing typed panels.
- Preserve legacy compatibility blocks, runtime dispatch, generated Lua stubs,
  reversible block source editing and the snippet gate used by recording/image
  generation. A name containing `legacy` does not prove code is unused.

## Verification

2026-10-12 user clarification: confirmation/layout/close-dialog functions belong
to Debug, not Interface. Apply this to blocks, API references and the right-side
Debug entry. Page parameters, widget operations and UI event waiting remain under
Interface. Frame crop/transform/PNG saving belong to Image processing; Unix and
monotonic clock reading belong to Time. Persisted kinds and protocols do not change.

Tests require every registered block and API to appear exactly once in its
respective catalogue, generated metadata to match, and API references to be
non-insertable. Existing image/recording snippet safety tests remain active.
