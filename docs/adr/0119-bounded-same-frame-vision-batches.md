# ADR 0119: Bounded same-frame vision batches

- **Status:** Accepted for the second performance phase
- **Date:** 2026-10-17

## Context

The runtime already supports `Screen.findImages` for one query containing
multiple templates. A visual flow can still contain several independent image
blocks that inspect the same captured frame. Executing those blocks one at a
time repeats frame validation and matcher setup, even though the pixels and
prepared templates are immutable. The public Lua/API contract must remain
unchanged while this optimization is evaluated.

## Decision

`automation-core` now exposes a bounded execution primitive for up to 16
independent first-match queries and 64 template references in total. It resolves
one leased `FrameView`, reuses the prepared template cache, consumes one explicit comparison budget, checks the
same cooperative cancellation callback before each query and inside the pixel
matcher, and returns one result slot per input query in the original order.
Each query retains its own ROI, direction, tolerance, similarity threshold and
template priority. Empty or oversized work is rejected before matching.

The primitive is not wired into the compiler or Lua runtime yet. That remains a
separate integration step requiring proof that adjacent visual blocks have the
same frame lifetime and side-effect ordering. Existing `findImage`,
`findImages`, and `findAllImages` behavior therefore remains unchanged.

## Consequences

- Matching a batch cannot trigger a second screen capture or duplicate frame
  pixels.
- A timeout/cancel can stop a batch without leaving partial results visible to
  the caller.
- A shared budget makes worst-case work explicit instead of multiplying a
  per-template budget by batch size.
- Compiler fusion is deliberately deferred until node identity, resource
  release, action side effects, and highlighter ordering are proven safe.
