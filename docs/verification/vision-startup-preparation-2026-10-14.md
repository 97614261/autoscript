# Vision startup preparation

Date: 2026-10-14. Scope: ADR0107, not final provider/device acceptance.

## Implemented boundary

- Protocol27 appends begin/cancel preparation and a fixed one-shot completion
  callback; existing transaction positions and script API contracts are retained.
- Studio editor run, isolated selected-node step and project/configuration launch
  pass digest-verified compiled requirements into RuntimeClient. Source bytes and
  requirements are copied before asynchronous startup. Known empty requirements
  perform no SDK work; historical unknown requirements keep lazy runtime checks.
- Preparation occurs after resources/UI configuration but before nativeStart,
  reserving one worker with no replacement queue and a 30-second deadline.
  Cancelled deadline entries are removed rather than accumulating until expiry.
  No component APK/model scan/hash or SDK work occurs on Binder/Root/VM workers.
- Fresh selection/compatibility verification runs on resolver IO. The first
  preparation uses a private 8x8 RGBA synthetic fixture through the exact backend
  to initialize it; later preparations reuse the initialized session but recheck
  its precise selection. Results are neither displayed nor logged. No capture,
  touch, private script, frame registry or alternate algorithm is involved.
- Stop/cancel/close invalidate readiness immediately. A native call that ignores
  interruption still owns its busy lease until it returns, and cannot publish
  late success. Failure/status payloads are fixed, not arbitrary exception text.
- A one-use, 30-second readiness receipt binds service generation, frozen Lua
  SHA-256 and exact loaded identities. Start rechecks bounded fixed selection
  records without hashing APKs; missing/cancelled/changed/expired receipts fail
  explicitly rather than falling back to raw startup. Raw historical requests
  that never requested preparation retain their prior behavior.
- Provider mutation checkpoints ask the connected service whether preparation
  is active. Preview and startup also reject overlapping preparation/work. UI
  management does not grant permissions, select on import or restart processes.

## Initial host evidence

- Runtime/client/Studio targeted compile/tests passed before final guards.
- Preparation controller tests cover negative generation, empty demand, early
  cancel, native call ignoring interrupt, one-shot timeout, environment change,
  source mismatch, changed selections, cancelled ready receipt and receipt expiry.
- Resolver tests cover real synthetic-buffer geometry, precise selection changes,
  no repeat inference for initialized sessions, and failed initialization not
  reported as available readiness. These are injected JVM backends, not real SDK
  device tests.
- Base isolated service/client gate passed: service 133 tests (one existing skip),
  client 31 tests; zero failures/errors.
- Rust full formatting/strict Clippy/workspace/schema/codegen passed: 522 JSON
  documents, 185 schemas, 159 APIs, 175 blocks.

The first full gate deliberately rejected four positive Runner fixtures still
using protocol26 after the protocol27 change. Fixtures now derive the current
protocol, while an explicit prior-protocol rejection test remains. That failed
build did not install an APK. Old user-imported templates are not rewritten or
deleted; protocol26 templates require a compatible replacement for new exports.

## Remaining gates

Base/CV-only/ONNX-only service/client profile gates have passed with isolated
outputs/caches: 133 service tests per profile (one existing skip), 31 client tests,
zero errors/failures. Base completed in 58s, CV-only in 1m35s, ONNX-only in 50s.
These JVM profile gates compile their selected backend source sets; they do not
load Android SDK native libraries or exercise the fixed provider on a device.
Final `build.cmd -Verify` passed in 7m37s (1075 tasks, 52 executed, 1023
up-to-date), including Studio 681 tests, service 133 tests and client 31 tests,
all with zero failures/errors. Studio/service each retain one existing skip.
Lint: zero errors, 36 warnings, 16 hints. MuMu0 updated successfully at
127.0.0.1:16385; app data retained. Final APK is 178,413,676 bytes, compressed
entries 172,588,513 bytes and layout/signing/padding 5,825,163 bytes. Default full
development arm64-v8a/x86_64; no embedded Runner; model hash unchanged. Final
repository-layout check after follow-up links: 223 links, no local-only references,
22 Rust members.
Standalone Runner packaging still derives families from declarations and has not
yet been switched to compiled-demand metadata. Function/workbench early preflight
also remains. Actual provider import/SDK initialization/recognition, API24/25/ARM,
overlay lifecycle, package/performance/memory and release acceptance remain.
Default Studio is still full. This report does not prove those remaining gates.
