# ADR 0084: Observable selected prompt tests and opaque session identities

Date: 2026-10-12
Status: accepted, implementing the user's single-step and live-highlight correction

## Decision

Runtime service generation is an opaque nonzero signed 64-bit identity, not a
positive counter. Execution-position validation accepts every nonzero generation,
including negative values, and continues to require a positive run revision.
Equality, project binding, ended-run rejection and stale-message gates remain.
No protocol version or generation format changes are required.

Idle selected-subtree testing normally ends immediately. A persistent
`Prompt.show` from that subtree would consequently be removed before the UI can
draw it. For this isolated editor test only, freeze a wrapper that routes
`Prompt.show` to the existing timed toast channel. Preserve the project's style,
duration and prompt master switch. This is bounded test presentation, not a change
to the stored block, generated artifact or formal run semantics. Explicit stop
still clears every prompt; a new run invalidates old previews.

Normal editor Run, formal Launch and paused-VM stepping retain existing prompt
semantics. They close persistent prompts on termination; short tips expire.
High-frequency execution positions remain latest-only, never a promise to render
every fast node or artificially delay scripts for animation.
