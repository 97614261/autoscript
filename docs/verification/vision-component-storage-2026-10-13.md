# Private provider storage and preparation: 2026-10-13

## Implemented source

- `AndroidVisionComponents.store` selects the host's private `vision-providers`
  directory and current installed publisher certificate, and uses the real
  PackageManager manifest reader. Neither location nor trust comes from scripts
  or imported metadata. This is distinct from Runner packaging templates.
- `VisionComponentStore` stages an owned read-only APK, validates it, then publishes
  by content hash. Duplicate imports revalidate without overwriting. Explicit
  deletion only targets a selected hash-format APK. Unknown files are untouched.
- Capacity limits: 8 APKs/1 GiB plus one bounded import; 2 prepared snapshots/
  512 MiB. A JVM lock and nonblocking FileChannel lease serialize registry access
  across host processes. No busy-spin or unbounded lock wait is introduced.
- `prepare` freezes the selected APK and extracts only the selected process ABI's
  two allowlisted native libraries plus the fixed ONNX model if relevant. Every
  executable destination is opened, marked read-only before any bytes are written,
  synced and content-checked. Read-only is not merely applied after copying.
- `PreparedVisionComponent` holds independent files and their byte counts/hash
  evidence; verification rejects changes, writable files, links and late use.
  Closing only removes tracked owned files and empty owned directories; unknown
  snapshot contents are preserved and cleanup can be retried.
- Prepared snapshots now publish a read-only `complete.marker` only after the
  frozen bytes verify. `recoverPrepared` removes only recognizable uncommitted
  snapshot shapes and retains committed snapshots or unknown contents for the
  future fixed loader; it does not recursively sweep the private directory.

## Host evidence

The initial 12 storage tests passed using real exported, signed provider artifacts
and an independent publisher trust fixture. Covered content-addressed duplicate
import, exact-ABI read-only preparation for both SDKs, ONNX model ownership,
independent frozen copies after deleting the import, two-snapshot limits,
cancellation before/during extraction, wrong publisher/kind, invalid IDs,
nonblocking held registry lock, writable/corrupt artifacts, extracted-byte
tampering, late access and preserving unknown files during cleanup.

The three additional tests (eight-artifact limit, explicit unsupported process
ABI and sealing before the first executable byte is read) also passed in the
full verification gate: 15 storage tests, no failures, errors or skips. The 16th
case, a real independent JVM holding the registry lock, passed in a separately
filtered run together with `:android:vision-components:lintDebug` (209 actionable
tasks). After releasing the helper process, inventory succeeds normally. These
host filesystem tests do not prove Android device acceptance.

`build.cmd -Verify` passed: 1042 actionable tasks, Studio Debug build and Lint,
SPI/validator/storage tests, native/model/layout validation and automatic update
to MuMu 0 with app data retained. The full-profile APK is `175,908,142` bytes;
the identical aligned APK size does not imply the runtime split is delivered.

## Still incomplete

These are library entry points, not yet a user-facing import panel or integrated
runtime path. SAF cancellation, actual PackageManager acceptance, Android24/25/34
read-only filesystem behavior, crash recovery of committed snapshots, loader
runtime wiring, availability IPC and run/test UI preflight remain required. The
new recovery scan only handles recognizable uncommitted snapshots; it is not yet
device acceptance.

## Fixed loader boundary

`FixedVisionProviderLoader` now exists in `runtime-service` and accepts only a
`PreparedVisionComponent`. It re-verifies the snapshot, uses the descriptor's
allowlisted provider class with a host-owned `PathClassLoader`, checks the SPI
version/kind, and retains the loader for backend lifetime. It does not expose
script paths, arbitrary classes, or dynamic native unload. Runtime-service still
needs lifecycle/availability IPC wiring before this becomes the active vision
backend.
The SPI also defines the shared five-state vocabulary (`AVAILABLE`, `MISSING`,
`INCOMPATIBLE`, `CORRUPT`, `DEVICE_UNSUPPORTED`); the loader's bounded `inspect`
method maps a prepared snapshot to that vocabulary without claiming recognition
success.

The runtime Binder contract now exposes a bounded `getVisionAvailability()`
query. In this increment it reports the compile-time runtime profile only
(`opencv=...`, `onnx=...`); it deliberately does not claim that a private
provider snapshot is installed or that recognition succeeded. Replacing this
source with prepared-store state remains part of lifecycle integration.
`RuntimeClient.visionAvailability()` exposes the same bounded map to Studio
callers without passing image bytes or provider paths.

## Image workbench increment

The Studio image workspace now keeps a bounded eight-step in-memory preview
history and offers a grayscale preview action. The action never changes the
captured source until the user explicitly saves; saving uses the currently
previewed bitmap and still preserves the existing resource/ROI validation. It
is a UI preview convenience, not a replacement for the Rust runtime transform.
No orphan sweep, recursive unknown-data cleanup, SDK download, arbitrary native
path loading, capability migration or algorithm fallback is introduced. Default
Studio remains full. Native loading is not implied by a prepared snapshot.

## Stabilization follow-up

The initial backend-lifetime wording above is superseded by
[ADR0100](../adr/0100-vision-provider-process-lifetime.md): native files and code
are pinned for process lifetime, independently of closable SDK sessions.
Ownership locks protect live snapshots; recognized new snapshots after owner
death can be reclaimed, while legacy complete snapshots and unknown content
remain preserved. Host tests and final build/install results are recorded in
[the lifetime verification](vision-provider-lifetime-2026-10-13.md).
Production provider dispatch and Android acceptance remain incomplete.
