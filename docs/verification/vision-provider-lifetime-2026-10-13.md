# Vision provider stabilization: 2026-10-13

## Changes

Implemented [ADR0100](../adr/0100-vision-provider-process-lifetime.md).

- Loader pins the exact artifact/ABI, class loader and snapshot lease before
  provider initialization. Backend close cannot release native files. A different
  artifact/ABI for an already pinned kind explicitly requires process restart.
- New sessions recheck the actual pinned snapshot, not just a new imported copy.
  Cancellation checkpoints remain available during hashing/loading boundaries.
- Session close is idempotent and further execution is rejected, including when
  the SDK's close reports a failure.
- Prepared snapshots hold an empty `owner.lock` FileChannel lease. Recovery
  skips live snapshots without hashing or deleting them. Only recognized unlocked
  transactions are reclaimed. Unknown content and legacy complete snapshots
  without ownership evidence remain untouched.
- Cleanup shares the nonblocking registry lock, retains liveness protection
  while deleting executable bytes, and deletes child directories before parents.
  Busy/failed explicit cleanup can be retried.
- CI now includes SPI and signed component/storage unit tests, matching the
  default local Verify gate. Build Tools selection no longer requires 35.0.0.

## Evidence

`scripts/check-rust.ps1 -Offline` passed formatting, strict Clippy, workspace
tests, schema validation and generated-contract comparison. Windows incremental
cache hard-link fallback warnings remain; no failed Rust gate.

`build.cmd -Verify` completed successfully in 4m58s, 1075 actionable tasks:

| Suite | Cases | Failures/errors | Skips |
|---|---:|---:|---:|
| Studio | 650 | 0 | 1 |
| Runtime service | 116 | 0 | 1 |
| Vision components | 41 | 0 | 0 |
| Vision SPI | 2 | 0 | 0 |

The component cases include an independent JVM holding a snapshot lease,
recovery after that owner exits, live tampered snapshots, busy-registry close
retry, unknown content and legacy complete-snapshot preservation. Loader
process-pin/session tests exercise host lifecycle policy; they do not initialize
an actual provider SDK on Android.

The initial stale-copy fixture attempted to copy Windows' live exclusive lock
file and failed. It now copies executable bytes separately and creates its own
unlocked simulated dead-owner file; the final 41-case suite passed.

Studio Lint: 0 errors, 37 warnings, 16 hints. Debug package passed ZIP layout,
native ABI/model and repository checks. APK: 175,914,803 bytes; compressed ZIP
payload: 172,218,810 bytes; layout/signing/padding: 3,695,993 bytes. These are
package observations, not recognition performance claims.

The full-profile development package (arm64-v8a + x86_64) updated MuMu instance
0 successfully with existing app data retained. No private script was executed.

## Still pending, not release claims

- User-facing provider import/selection and actual service dispatch integration.
- Real installed/prepared/loaded availability and run/test/function preflight.
- Android24/25/34 PackageManager/read-only/code-loading acceptance, synthetic
  OpenCV/ONNX inference through loaded artifacts, and supported-device coverage.

Default Studio remains full; separate Runner templates are packaging artifacts,
not installed runtime providers. No downloader, arbitrary native/model loading,
capability migration or project-data deletion was introduced. This stabilization
batch can close without treating the remaining component branch as delivered.
