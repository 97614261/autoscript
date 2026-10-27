# Bounded same-frame vision batch verification (2026-10-17)

## Scope

This check covers the new `automation-core` execution primitive only. It does
not claim compiler fusion, Android/JNI integration, MuMu performance, or
private-script behavior.

## Checks

- One leased frame can serve multiple independent template queries without a
  second frame lookup or pixel copy.
- Results remain in query order, including explicit `None` misses.
- A single comparison budget is consumed across the whole batch.
- Cancellation is honored before the next query and from inside matching.
- Empty, more-than-16-query, or more-than-64-template batches are rejected
  before matching.

## Command

```text
cargo test -p automation-core
```

## Result

Passed: 28 tests, 1 ignored benchmark, 0 failures.

The next phase must prove safe visual-flow fusion before calling this primitive
from generated Lua. No production runtime path was changed in this phase.
