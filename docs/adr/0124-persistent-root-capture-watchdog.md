# ADR 0124: Persistent Root Capture Watchdog

- Date: 2026-10-18
- Status: Accepted

## Context

The Root capture bridge has a hard five-second deadline and must be killed when
the bridge stops responding or a priority cancellation arrives. The previous
implementation created and joined a new watchdog thread for every capture.
That was correct but added scheduler and allocation churn to a hot loop, and it
made repeated capture failures harder to reason about.

## Decision

`RootInputProcess` owns one long-lived watchdog thread when it is the capture
bridge. A capture arms a single lease with the child PID, deadline and
`InputCancellation`; the lease is finished before the caller reaps or replaces
the child. The worker remains parked while idle, wakes on arm/finish/cancel or
deadline, and kills only the PID held by its current lease. Shutdown marks the
worker inactive and joins it before bridge teardown.

The existing absolute read deadlines, cancellation semantics, failed-closed
bridge handling and PID ownership fence remain unchanged. The input-only bridge
does not create a watchdog. No protocol, capture format or timeout value is
changed.

## Consequences

- Repeated captures reuse one worker and avoid per-frame thread creation.
- A finished lease cannot kill a later capture because each lease has its own
  completion flag and the worker consumes one arm at a time.
- The bridge still fails closed after a timeout or malformed response and the
  child is reaped by the owning dispatcher.
- Android target compilation is required; host tests cover the shared protocol,
  but cannot execute the Android process bridge.
