# Fixed vision component validator: 2026-10-13

## Implemented

- Added `android:vision-components`, a host verification library depending only
  on the narrow SPI and the repository's existing Gson 2.11.0 (Apache-2.0).
  No additional vision SDK, JNI, Root, Lua or private-key dependency was added.
- Studio's APK signer now delegates v2 verification to the same extracted
  RSA/SHA256 verifier; private-key signing remains in Studio.
- Strict descriptors reject duplicates, trailing data, malformed UTF-8,
  unexpected fields, wrong numeric types, future SPI/SDK versions, arbitrary
  provider class/model paths, duplicate or unsupported ABIs and wrong licenses.
- The archive validator checks caller-supplied publisher trust (not a certificate
  in the descriptor), exact native and asset sets, 64-bit little-endian ELF
  machine identity, fixed model bytes/hash, license budgets, path traversal,
  duplicate ZIP entries, entry count, total inflated size and before/after APK
  content identity. APK limit is 256 MiB, inflated contents 512 MiB, descriptor
  8192 bytes, license 1 MiB and entries 10000. Checkpoints support bounded caller
  cancellation/deadlines before and during IO; no executable loading occurs.
- `VisionComponentManifest.read` reads actual PackageManager metadata, including
  all activities/receivers/services/providers/instrumentation and permissions.
  Components must have code but no Android executable component or permission.
  This reader is for the future Android import path; no user-facing import or
  loading is claimed delivered yet.

## Evidence

`:android:vision-components:testDebugUnitTest` passed: 16 tests, no failures or
skips. The task exports the two real signed SDK APKs, so artifacts are not
silently omitted when fixtures are absent. Trust is taken from the publisher's
development key store, independently of the APK under test. Mutations are
re-signed with that public development fixture identity to prove rejection by
content gates, not just rejection due to invalid signatures.

Cases include wrong publisher, post-signing tampering, signed extra native/asset
entries, wrong ELF ABI, model bytes modified under the correct claimed hash,
invalid manifest identity/components/permissions, fixed descriptor syntax/types,
resource limits and cancellation. Production keys or scripts are not used.

Host JVM tests supply typed manifest fixtures. They do not exercise Android's
PackageManager parser; actual device verification remains a separate gate.
No speed or memory-efficiency conclusion follows from these tests.

Full `build.cmd -Verify` passed after extraction: 1042 actionable tasks, Studio
Debug APK `175,908,142` bytes, native/model/layout checks passed, and automatic
MuMu 0 update installed successfully with data preserved. This is installation
of the still-full Studio, not optional provider loading acceptance. The first
full retry exhausted the C1 CodeCache; the successful one used process-local
Gradle daemon arguments with `-XX:TieredStopAtLevel=1` and
`-XX:ReservedCodeCacheSize=512m`, not `JAVA_TOOL_OPTIONS` or a permanent setting.

## Remaining gates

Private import and read-only preparation library code has since been added under
ADR0098; see the [storage record](vision-component-storage-2026-10-13.md). Its
user-facing/SAF path, crash recovery, fixed loader, native lifecycle, service
availability IPC, run/test preflight and device synthetic fixtures remain
incomplete. Default Studio retains the full profile.
No downloader, SDK installation, fallback algorithm or project migration was
implemented by this batch.
