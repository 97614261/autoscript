# Vision performance phases 2-3 · 2026-10-17

## Phase 2: same-frame batch safety

The compiler audit found two different cases:

- Explicit-frame visual blocks already share the caller-owned frame. They do
  not perform another `Screen.capture()`.
- `autoCapture` blocks intentionally capture at each node. They can observe an
  asynchronously changing screen, frequency gates, actions, and execution
  highlights. They are not fused by default because doing so would change
  script semantics.

The bounded `automation-core` batch primitive is therefore kept as an internal
candidate. It supports up to 16 queries and 64 template references, one frame
view, one shared comparison budget, cooperative cancellation, and ordered
results. It is not wired into generated Lua or the public API in this phase.

## Phase 3: host benchmark

Command:

```text
cargo test -p automation-core benchmark_same_frame_batch_vs_sequential_template_search --release -- --ignored --nocapture
```

Fixed synthetic input: 320x480 RGBA frame, four 16x16 opaque templates, full
ROI, exact similarity, 30 samples after five warmup rounds. Values are
microseconds for the complete four-query operation:

| Path | P50 | P95 |
| --- | ---: | ---: |
| Existing sequential calls | 4,238 | 4,576 |
| New same-frame batch candidate | 4,512 | 5,543 |

The candidate is slower under this fixed host input, so it is **not** promoted
to the production/compiler path. The result is useful as a regression guard,
not as an Android or Root screenshot performance claim. The dominant device
cost remains screenshot acquisition/transport, which this CPU-only benchmark
does not measure.

## Verification

- `cargo test -p automation-core`: 28 passed, 1 ignored benchmark.
- The explicit benchmark passed and reported the values above.
- `cargo check -p engine-jni --target x86_64-linux-android` passed.
- `cargo fmt --all -- --check` and `git diff --check` passed.
- No APK was installed and no private script was run.
