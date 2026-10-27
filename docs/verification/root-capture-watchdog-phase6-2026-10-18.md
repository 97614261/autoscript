# Root capture watchdog phase 6 - 2026-10-18

## Scope

This phase replaces the per-capture `root-capture-deadline` thread with one
parked watchdog owned by each Root capture bridge. It does not change the
capture protocol, five-second deadline, cancellation behavior or pixel format.

## Checks

- `cargo fmt --all -- --check`: passed.
- `cargo test --offline -p root-daemon -p root-client -p engine-jni`: passed
  (12, 25 and 40 tests respectively).
- Android target compilation passed:
  `:android:runtime-service:buildRootDaemonArm64_v8a` and
  `:android:runtime-service:buildRootDaemonX86_64`.
- Studio Debug and the synchronized androidTest APK were built and installed on
  `emulator-5554` before the performance run.
- The public synthetic performance fixture passed all four cases. The latest
  log contained compact `captureCacheHit` and `matchWork` fields; the large
  missing ROI reported 430,867 candidates and 430,867 exact comparisons.
- After the final rebuild with the persistent watchdog, the same fixture still
  passed: small ROI capture P95 0.748 ms / match P95 0.031 ms, large ROI
  capture P95 25.297 ms / match P95 7.422 ms, and large missing ROI capture
  P95 23.699 ms / match P95 6.980 ms. These are independent operation
  percentiles, not a sum.

## Boundary

This is source and synthetic MuMu evidence only. It is not ARM64/API24 real
device acceptance, not a claim that every OCR or full script loop is below
100 ms, and not validation of a user's private script.
