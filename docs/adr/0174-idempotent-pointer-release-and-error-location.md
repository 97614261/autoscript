# ADR 0174: Idle Pointer Release And Runtime Error Location

Date: 2026-10-28

## Decision

A standalone script pointer-up request may succeed without sending input when the
requested pointer is already released. The runtime adapter checks the arbiter's
actual ownership after pending cleanup and lease expiration have completed.
Stopped, cancelled, expired, foreign-owner and unconfirmed-cleanup requests still
fail. The strict transaction validator continues rejecting malformed sequences;
multi-command transactions and pointer down/move semantics do not change.

Lua failures include the failing task's last compiler-provided flow and node
identities before the task context is removed. Structured failure events remain
the only source for editor navigation; free-form logs and Lua stack text are not
interpreted as navigation targets.

Migrated color checks may expose fresh-capture as a single configurable block,
while preserving the original legacy per-channel color tolerances and ROI. A fresh
capture is still an owned temporary frame which must be released after use. It is
not reused by subsequent recognition calls. Private migration artifacts remain in
ignored local build directories and are never embedded in the public App.

The internal `Screen.cache` call only registers task ownership of this fresh
frame. It does not capture again, copy pixels or retain the frame for the next
recognition call. The visual editor exposes one operation; release remains an
internal resource-lifetime step.

## Validation

Use targeted host tests for idle/owned/foreign/stopped releases, cleanup failure,
expiry/cancellation, task-scoped error identities and fresh-frame lifetime. Host
tests do not establish real game input, device screenshot or recognition results.
