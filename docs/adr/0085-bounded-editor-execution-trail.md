# ADR 0085: Bounded execution trail for fast editor rows

Date: 2026-10-12
Status: accepted, correction requested by user

## Finding and decision

A pump can execute several checkpoints before returning. Latest-only position
sampling consequently misses fast rows such as dynamic prompts between slower
image searches. Signed generation validation fixes delivery but not this loss.

Capture up to 32 recent entered node identities in the Rust executor, independently
of log switches. Drain atomically with the latest position. The service combines
bounded batches at its existing 20Hz ceiling; the client merges pending batches
instead of dropping their fast rows. Protocol 24 carries these bounded identities
and a sample sequence, with existing project/generation/run gates unchanged.

The editor shows a bounded unique pending trail, at most 32 rows, using short
display-only intervals. A trail row different from the real latest position is
labelled "just executed", never "executing". When drained, follow the real latest
row again. Stops, pauses, new runs, disconnects and disposal clear/cancel the trail.
No script sleep, intentional VM throttling or unbounded per-node Binder messages are introduced.

This does not promise a rendered frame for every arbitrarily fast loop node;
overflow retains only bounded recent evidence. No source, expressions, values or
prompt text is carried in the trail or persisted by this feature.
