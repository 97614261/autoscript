# ADR0146: Authenticated capture pipeline

Date: 2026-10-23. Status: implementation measured; pipeline not enabled by default.

## Measurement Gate

Two MuMu full-workflow pipeline runs and a repeated buffered baseline did not show
stable tail gains in every scene. Android's default connector therefore does not
request CAPTURE_PIPELINED. The implemented, authenticated optional protocol and its
fault tests remain available, but production keeps the buffered stream. This is not
a released default pipeline speedup or an ARM device acceptance claim.

The measured follow-up enables the existing sha2/asm implementation for Android
x86_64 as well as AArch64. Upstream SHA instruction detection remains; x86's scalar
assembly fallback requires no forced CPU features. HMAC-SHA256 and authenticated
packet bytes are unchanged. Public protocol vectors and CPU benchmarks for both
x86 variants pass on MuMu; final package workflow acceptance is recorded separately.

## Decision

Pipeline the existing full-screen persistent Root bridge readback with authenticated
60KiB packet transfer, instead of collecting the entire frame before sending metadata.
No region capture, scaling, stale-frame reuse, algorithm or model changes are implied.

The optional CAPTURE_PIPELINED capability requires stream, stage and transfer timing
capabilities. An explicit tag3 initial metadata message has geometry/identity only;
acquisition/stage values are not final until a mandatory tag4 completion. Completion
binds capture identity, exact pixel length, bounded acquisition/stage timings and data
packet count to the already authenticated request. Every packet retains HMAC, sequence,
offset and request checks. A client publishes no frame before validating completion.
Legacy peers and bounded screencap retain the existing buffered stream and summary.

The bridge is read synchronously in bounded chunks, so socket writes supply backpressure;
no producer thread or frame-sized queue is added. The original five-second bridge deadline
and its watchdog/cancellation token remain active until the trailer is fully validated.
Stop and control failure can cancel between packets or while a pipe read is blocked.
The last chunk is not emitted until the bridge trailer succeeds. After metadata, failure
must invalidate the incomplete connection; it cannot fall back/replay another screen.
Fallback before metadata remains bounded by the original capture deadline.

Root readback, authentication and transmission now overlap. Stage durations and quantiles
must not be added. Compare the same exact-package synthetic workload before/after, preserve
failures, and keep CPU benchmarks, host checks and device acceptance distinct. MuMu checks
do not claim native ARM/API24 acceptance. Only adopt measured gains; online publishing,
private scripts and input-method settings stay out of scope.
