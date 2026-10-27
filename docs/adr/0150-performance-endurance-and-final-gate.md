# ADR 0150: Endurance, memory attribution and the fixed final performance gate

Status: implemented and MuMu performance/endurance verified, 2026-10-24;
the unchanged lowercase OCR accuracy exception remains explicitly recorded.

## Scope

The user authorized batch four, stages 11-12 of the fixed performance plan.
Exercise sustained capture/match/input/log load, ownership and lifecycle
pressure, then a unified exact-candidate regression/performance gate. Operate
only MuMu 0 and newly owned public QA projects. Do not connect the phone,
change IME/settings, clear data, restart the emulator or publish online.

## Decisions

Preserve the default full/V4 backend, the 80 ms freshness limit, disabled
experimental capture pipeline, authenticated stop and uncertain-touch lockout.
Do not introduce forced GC, periodic process restarts or cache-age extensions.
Any production correction requires a focused reproduction and a rerun on its
new exact APK. A build is not device acceptance.

Separate Studio, Runner, Root daemon and its verified direct bridges in memory
observations. Bind Root identity to the installed package's native executable,
not a basename or a private command line. Collect only fixed numeric process
accounting, never memory dumps, command arguments, keys or user data. Report
unavailable fields as unavailable, not zero; PID changes split series. PSS,
resident allocator pages, allocated native bytes and retained objects are
different measurements. Only observable ownership/counters or allocation
evidence can establish a leak; a finite flat curve cannot prove zero leaks.

Keep observations bounded and off the production execution path. Exercise
stop/restart and a post-stop idle period without hiding the final samples.
Maintain progress and final summaries without duplicating the entire sample
set in the instrumentation output budget.

Freeze production and test digests before unified regression. Correctness,
resources/cleanup, full-operation timings and SDK accuracy remain separate
gates. Timers include dispatch and acknowledgements when labeled, disclose
observation pacing and never add overlapping stage percentiles. Unsupported
recording, ARM/API24 and native-device acceptance must not be marked passed by
the MuMu gate. The user wants a concise outcome, not a new standalone report.

## Verification

### Terminal stop observation correction

Rapid start/stop found an occasional wait for STOPPED after Stop was accepted.
The observer published one native-state read, then used a second read to decide
whether to observe again. A STOPPING-to-STOPPED transition between those reads
could suppress the next observation without ever publishing STOPPED, with the
normal pump already removed. Decide whether to reschedule from the very state
just observed/published, not another native read. Preserve the priority stop
path, cleanup confirmation, original observer interval and failure semantics.
Add a deterministic transition-between-reads regression; exact-candidate
workflow, stop performance and endurance must be rerun after this correction.

### Capture watchdog correction

Final regression exposed an intermittent switch to the existing screencap
fallback. Inspection found a concrete watchdog race: a completed request can
leave its arm in the worker's pending slot until the worker is scheduled, so
the next serialized capture is spuriously rejected as busy. An atomic finished
flag also does not fence a kill callback against the caller reaping its child.
This is a proven code race, not yet proof of the observed device trigger.

Keep one worker and one active arm, but synchronously clear the exact arm when
its lease finishes. Serialize completion with the kill callback so completion
returns only after any in-flight kill has returned, before the child may be
reaped. A stale worker must neither clear a newer arm nor kill after completion.
Keep active-overlap rejection, event-driven cancellation, the existing deadline
and bounded fallback unchanged. Exercise delayed-worker rearm, cancellation,
deadline, shutdown, in-flight completion and pressure on the host; rebuild and
repeat exact-candidate device acceptance after changing production code.

Use parser/malformed-accounting tests, bounded cache/ownership pressure tests,
host formatting/static/unit/build checks, a 30-minute real Root workload and
same-candidate capture/vision/runtime/editor/UI regressions. Retain exact
digests and failed attempts locally; update this decision with the actual
outcome before claiming completion.

### Implemented verification safeguards

FramePool pressure covers 20,000 explicit-release/task-cleanup cycles, the
capture cache covers 10,000 replacement/expiry/clear cycles, and the two-entry
regex cache covers 2,000 task-generation/eviction cycles. Weak allocation owners
must disappear after the final in-flight consumer drops; counters alone do not
establish release. Rust workspace tests, strict all-target Clippy and formatting
pass, as do the Android unit tests and Lint.

MuMu's native process `dumpsys meminfo` omits its PID heading and does not expose
allocator heap totals. Accept its PSS only after matching `/proc` start ticks
and the allowlisted executable before and after the read, following the verified
Root parent relationship. Leave unavailable allocator fields null rather than
interpreting system zeroes as no allocations. Modern, legacy, missing-field,
native-heading and PID-mismatch parsing tests pass. The initial incomplete
sampler evidence is retained separately; the corrected sampler does not restart
the running App. Only the pre-endurance App restart was explicitly permitted;
timeouts never trigger an App restart or an automatic retry.

The final UI attempt exposed a test-observation error: synchronous UiAutomation
injection spent about ten seconds per DOWN/UP pair while waiting for animation
transactions, outliving a three-second preview. The two-argument method also
waits for animations when sync=false (confirmed by its framework source and
5,006-5,017 ms event samples). Use the exact framework TestApi overload, when
available, with synchronous delivery but waitForAnimations=false. Retain
nonzero press duration, stable observed geometry and every actual UI
postcondition; do not change system animation scales. Older systems without
that overload retain their public API and disclose the animation wait. Record
framework dispatch timing separately, never as production Root input latency.
Refresh virtual Compose
nodes and await recomposition before testing enabled actions; initialize the
window baseline only after the owned Activity is accessible. Log selection
requires the one newly running owned record, not asynchronously persisted older
sessions. Failed attempts remain evidence and are not reclassified as passes.

### Current candidate gate, 2026-10-24

The corrected production APK SHA-256 is
`6028c37267776f6a14230bc7ea749f41e92db86d4322298717d22bbe1ddd2c1d`,
with test APK
`48b1dc799ab72ba3aec1161ed50640b3dd12c190201167ca231d7decdad5e777`.
`build.cmd -Verify` completed unit tests, Lint, package checks and installation to
MuMu 0, preserving data. Its Debug certificate SHA-256 remains
`8e48ea72d827f96ea57c6b7bf4d566bac7581fa23106324bf3296fa9e2024f6c`.

On that exact pair, editor 9/9, workflow 30/30, experience 21/21 and lifecycle
1/1 (20 real open/close cycles) passed with fixture cleanup. The region suite
then failed its portrait-restored pixel-settling check after four successful
portrait/landscape cases. Read-only system diagnostics showed persistent
`DispatchFrozen=true`, a system-owned `RotationLayer#0` and queued input aging
past 160 seconds after instrumentation exited. Retain the failure; it is not a
passing rotation result or proof of an App defect. The gate stopped, so the
remaining performance suites and this candidate's endurance are still pending.
Do not reuse the earlier candidate's successful 30-minute run as acceptance of
this production change. Emulator recovery requires separate user permission;
never restart or retry inside the endurance run.

On 2026-10-24 the user separately authorized one restart of MuMu 0 to recover
this system freeze and continue acceptance. Preserve all App data and the
failed evidence, recheck the exact installed APK pair, and start a new evidence
series after recovery. Do not splice old and new endurance samples or treat
this permission as allowing restarts during sustained load.

After recovery, region 6/6, expanded vision 10/10, mixed vision 3/3, runtime
7/7, editor 9/9 and workflow 30/30 passed on the same pair. The next experience
attempt observed a disabled accessibility parent after the owned synthetic
placeholder had been enabled; the saved synthetic screenshot shows the enabled
blue/white button. Refreshing the text node does not refresh a subsequently
retrieved cached parent. Refresh each candidate ancestor before checking its
enabled flag or requesting its action, without bypassing disabled state,
extending timeouts or changing the production component. Preserve this failed
attempt and rebuild/reidentify the test APK before repeating that UI gate.
The same parent-refresh rule applies to shared-widget action and branch-state
queries: text and ancestor snapshots must not be mixed. The intermediate test
APK passed the placeholder and deep-tree cases, then failed a branch-selection
observation; retain its exact tree/screenshot and refresh ancestor action/state
reads consistently before the next test-only build.

The final ancestor-refresh test APK SHA-256 is
`c7d3f5915d66bee0a284c81130396bc66825dff9be90ac0813d81388e4e4bdec`;
production remains the exact `6028c372...` candidate above. The unified gate
passed all 87 cases: experience 21, lifecycle 1 (20 open/close cycles), region
6, expanded vision 10, mixed vision 3, runtime 7, editor 9 and workflow 30.
All suites reported owned-fixture cleanup. No system settings, default model,
freshness limit or production UI assertions were changed to obtain these passes.

On MuMu 0/API32/x86_64 at 720x1280, the 100 warm mixed find/color/fixed-glyph
samples with a 200x122 template, similarity 1000, tolerance 0 and direction 0
measured complete Root tap/capture/three recognitions/release/dispatch
P50/P95/P99 of 28/35/39 ms for full capture (ROI [480,948,700,1090]) and
16/20/23 ms for explicit region readback (ROI [10,10,230,152]). Both groups
used SurfaceControl in all 100 samples. The first call and 10 warmups are
separate; following log write and 25 ms observation pacing are outside the
complete timer. These overlapping metrics are not additive or ARM guarantees.

### Exact-candidate endurance and process accounting completed

The same production/test pair passed all 13 endurance cases with owned-fixture
cleanup: 1,800,012 ms of load, 1,860,071 ms including idle observation, and
13,501 iterations. All 60 load samples and four post-stop samples retained the
same run/process identities, with no match miss or tap-counter mismatch.
Pause followed by authenticated Stop completed in 11 ms in this single sample;
this is not a guaranteed deadline. No restart or forced GC occurred during the
run. Runner PSS was 66,805 KiB at the first load sample, 65,695 KiB at the last,
64,807-70,304 KiB over load, and 63,767 KiB after 60 seconds idle.

The separate off-path sampler retained 155 valid observations across five
verified process identities. Load-series PSS first/last and ranges, in KiB:

| Role | First / last | Load range |
| --- | --- | --- |
| Studio | 98,569 / 94,157 | 94,065-98,569 |
| Runner | 66,614 / 64,122 | 64,122-71,216 |
| Root daemon | 5,010 / 5,010 | 5,010-5,010 |
| Direct bridge 6021 | 50,297 / 44,746 | 44,218-58,969 |
| Direct bridge 6047 | 46,887 / 46,113 | 46,085-50,635 |

Studio Views/windows remained 6/1 and Runner 19/0. Native allocated bytes were
available only for Studio (11,564 to 11,579 KiB) and Runner (10,440 to 10,273
KiB); Root/bridge allocator totals remain unavailable. Supplemental idle
observations include bridge 6047 rising to 58,314 KiB, above its load-only
range. The stop sampler retained 25 valid observations and nine unavailable
observations after expected process exit; unavailable does not mean zero.
Both direct bridges are reported generically, without inferring capture/input
roles. Finite ownership pressure and accounting found no proven unbounded
retention; they do not prove zero leaks or a flat curve for every process.

Expanded recognition measured P50/P95/P99 of 28/34/37 ms for three distinct
templates sharing full capture, 16/19/22 ms sharing explicit region capture,
and 86/101/104 ms with three independent full captures. Therefore this result
does not promise every combination completes below 100 ms. Ordinary batches
of 96 text/JSON/math calls measured 4/6/7 ms; 64-call regex batches measured
2/4/4 ms closed, 2/4/9 with editor, 2/3/4 with logs, and 2/4/6 with observer.
These include compiled Lua dispatch, operations and variable writes, excluding
following log writes and observation pacing.

### Independent SDK accuracy exception retained

The separately installed runtime SDK test APK SHA-256 is
`0f022ac7930757b917c5f5d7069265217801a1cf81a500c9ed85032a255e65f4`.
Its preprocessing suite passed 28/28 cases with exact tensor bits and zero
value differences. Gray ROI/missing/cancel/close and uppercase, digit and mixed
OCR groups passed, but lowercase monospaced OCR failed: expected lowercase
alphabet, actual `abcdefghijk1mnopqrstuvwxyz`, with 0/10 exact results. The raw
vision suite therefore remains FAIL, not an all-SDK accuracy pass.

The separate evaluation completed with allExact=false: original 21/30 exact,
tight ROI 21/30, white border 15/30, and 2x scale 21/30. Evaluation completion
is not production accuracy acceptance. Default V4 model SHA-256 remains
`e8770c967605983d1570cdf5352041dfb68fa0c21664f49f47b155abd3e0e318`;
no model replacement or automatic l/1 correction was applied. This independent
SDK process reported aarch64/64-bit on the x86_64-first MuMu host; treat it as
emulator/possibly translated execution, not native ARM acceptance or comparable
Studio full-pipeline performance.

Evidence is retained under `build/verification/performance-batch4/`, including
`final-parent-gate`, `final-endurance.log`, `final-process-memory.log`,
`final-stop-memory.log` and the independent `final-sdk-*` logs. Earlier failed
attempts remain intact. Final installed production and local production/test
APK hashes match the frozen 6028c372/c7d3f591 pair. Recording remains
UNSUPPORTED_DEVICE (honest rejection/recovery tested, not successful recording);
native ARM/API24 acceptance is outside this batch. No online publication was
performed.
