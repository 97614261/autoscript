# ADR 0149: Runtime and presentation performance, fixed stages 8-10

Status: implemented and MuMu regression verified, 2026-10-23. Successful live
recording remains unverified on this device; sustained load and the final
cross-stage gate remain batch four.

## Scope and Boundaries

Continue the user's fixed twelve-stage plan, batch three only: ordinary script
calls/scheduling, input/recording/stop response, and UI/log/startup overhead.
Batch four owns sustained memory/load and the final unified performance gate.
Remove the decorative title rows from Project and Profile as separately asked;
retain navigation, search, actions and real state. Do not change project data.

## Decisions

Retain the event-driven scheduler and priority stop channel. Do not merge
side-effectful operations, skip input acknowledgements, replay failed input,
relax touch cleanup, change cache age, or alter Lua/Unicode/error semantics.
Existing recognition-result parsing remains task/source bound.

Repeated regex compilation may use an executor-owned LRU of two successful
patterns, keyed by exact task token (including generation) and full UTF-8
pattern. Keep the existing 128-byte pattern, 1 MiB compiled program, 256 KiB DFA
and nesting limits. Cache neither source text nor results; reject invalid patterns
without retention. Remove task-owned entries at terminal/cancel cleanup; drop
with the executor. Debug output shows only occupancy, never patterns. No global
user-pattern cache or new dependency/API.

Recording filtering must inspect bounded 32-byte records without boxed-byte
lists; preserve exact bytes/order and half-open control bounds, retaining key
events as before. No change to Root injection or uncertain-touch state.

Optimize only measured/repeated presentation work. Log snapshot overlap must
preserve maximal suffix/prefix and repeated identical lines, with bounded
linear matching and no accumulated source history. UI projections are tied to
immutable data revisions, not animation clocks. Do not reduce execution events,
hide errors, poll faster, force GC, or delay scripts for rendering.

## Verification

Compare references/candidates on public fixed inputs, Unicode/errors/budgets,
record filtering and duplicate log snapshots. Use full script-call, Root input,
stop and real editor/log/watch comparisons on self-owned MuMu QA only. Bind
results to exact APK/configuration; distinguish backend submission from target
response and cold from warm. No phone, IME change or online publication.
The user requested concise results, not a new standalone long report.

### Final Evidence

The final full/development Debug APK is 96,221,382 bytes, version 0.1.3/code 4:
`7296ed7fbaf6fafbfa7cbbe0807ef002ccd1852456d9e50e471e2fe5a7cf10be`.
The test APK is
`5069687b6e599e885bc2c9751a91f018d4f8cdbdc48098b030538a34df3aa0a7`.
Installed APK hashes match both local files. The unchanged development signing
certificate SHA-256 is
`8e48ea72d827f96ea57c6b7bf4d566bac7581fa23106324bf3296fa9e2024f6c`.
Only MuMu 0 (`PGT-AN20`, API 32, x86_64, 720x1280) was operated; application data
was preserved. No phone, IME, emulator restart or online publication was used.

Rust workspace tests, strict all-target Clippy and formatting passed. The
Studio Verify build passed unit tests, Lint, native/package/layout checks and
installation. Recording filtering passed reference comparisons; duplicate log
snapshots passed exhaustive small-sequence and bounded 200-row comparisons.
Session tests verify that realtime log demand stops when minimized, hidden or
closed. The APK retains default full/V4, the 80 ms cache and the disabled
experimental capture pipeline.

The unchanged reference APK
`7d19ff0a90e50b947557522790ed980f78b93fce2702d45a7247f0cad111161a`
and candidate were alternated on the same emulator. There are two successful
candidate runtime rounds; the latest reference round uses the same seven-case
fixture. Each workload has 111 iterations, excluding the first 11 for 100 warm
samples. Millisecond timers include compiled Lua, dispatch, operations and
variable writes; following log writes and 25 ms observation pacing are outside
the interval. These are batch times, not per-call or process-cold latency.

Latest comparable rounds, P50/P95/P99 in milliseconds:

| Workload | Reference | Candidate |
| --- | --- | --- |
| 96 ordinary text/JSON/math calls, closed UI | 4/8/9 | 4/7/10 |
| 64 regex calls, closed UI | 25/30/33 | 2/3/3 |
| 64 regex calls, real editor/highlighting | 25/29/31 | 2/5/8 |
| 64 regex calls, real realtime-log window | 26/31/33 | 2/4/5 |
| 64 regex calls, real variable observer | 26/29/31 | 2/4/5 |

The earlier candidate closed-regex round also measured 2/3/3 ms. Ordinary calls
remain within noise; no speedup is claimed there. The UI comparisons also
contain the regex improvement and do not isolate drawing cost. Direct Rust
release microbenchmarks are separately labeled in local evidence and are not
used as Android latency.

The latest 100 warm Root taps measured acknowledgement 2/4/4 ms and own View
UP delivery 2.123/3.196/3.943 ms. Target timestamps combine nanosecond callbacks
with a millisecond script start; this is not target rendering latency and the
overlapping metrics must not be added. Candidate active-swipe stop samples were
4.201 and 7.041 ms, both with touch release confirmed; CPU-stop samples were
3.700 and 4.452 ms and wait-after-restart samples 3.284 and 6.335 ms. These are
individual observations, not stop percentiles or guaranteed deadlines.

Final same-package suites passed with owned-project cleanup: runtime 7/7 twice,
editor/step/pause/highlight 9/9, workspace 9/9, data/Root/workflow 30/30, mixed
vision 3/3 and home/shared-window experience 21/21. Project/Profile synthetic
screenshots were inspected after title removal. Workspace cases use synthetic
narrow/landscape-sized viewports and large Compose fonts, not new phone or
system-rotation acceptance. The recording case explicitly returned
`UNSUPPORTED_DEVICE` three times, with zero accepted recordings; this proves
honest rejection/recovery only, not successful recording or its device speed.

Mixed vision uses one fresh capture for find/color/glyph (65 glyph variants),
a 200x122 template, similarity 1000, tolerance 0 and direction 0. The complete
Root-tap/capture/three-recognition/release/dispatch warm pipeline was 28/35/36 ms
for full capture (ROI [480,948,700,1090]) and 16/23/25 ms for explicit region
readback (ROI [10,10,230,152]). It excludes following log/pacing and is a
regression observation of earlier batches, not a newly attributed batch-three
gain or an ARM result. Stage percentiles are not added.

Bound evidence, including intermediate build logs and final successful runs,
is retained locally under `build/verification/performance-batch3/`; it is not
published or used to claim completion of batch-four endurance/memory gates.
