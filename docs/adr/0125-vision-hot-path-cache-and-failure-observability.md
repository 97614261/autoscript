# ADR 0125: Vision hot-path cache and failure observability

- Date: 2026-10-18
- Status: accepted

## Decision

The Root client preallocates the bounded capture destination and copies streamed or chunked
payloads directly into it while reusing its authenticated packet buffer. OpenCV gray matching
keeps a bounded LRU of grayscale template bytes keyed by the template geometry, format and
SHA-256 pixel fingerprint. Cache entries never change the Lua/API contract and are discarded on
provider close.

Project summaries use a stamp-checked cache; manifest and state metadata changes invalidate the
decoded list, while visual node/block identity indexes reuse only an exact source-string match
and remain bounded. Runtime vision failures publish a stable `failureCode` and a coarse status
(`capture_failed` or `frame_failed`) without persisting free-form Lua, paths or backend messages.

## Constraints

- All buffers and caches remain bounded and cancellation/ownership rules are unchanged.
- A stale frame, template or project summary must never be used after its identity or on-disk
  stamp changes.
- The cache is an optimization only; a miss follows the existing capture or conversion path.
- Device performance and real-device acceptance remain separate from these static/unit checks.
