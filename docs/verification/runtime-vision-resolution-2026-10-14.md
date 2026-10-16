# Runtime fixed vision resolution: 2026-10-14

## Actual production wiring

[ADR0104](../adr/0104-runtime-fixed-vision-resolution.md) adds a service-owned
resolver used by both the script NativeVisionAdapter and RuntimeRecognitionPreview.
This is no longer an unused loader API, but is not complete on-demand delivery.

- Bundled kinds keep their existing implementation in the full/CV-only/ONNX-only
  profiles. A missing bundled kind uses only the fixed, explicitly selected
  private provider. Import does not select; missing/bad exact selections never
  choose another APK or a different algorithm.
- Selection is a bounded 68-byte versioned record, published under the component
  registry lock with fsync and platform atomic replacement (Android Os.rename;
  JVM host fixture uses Files.move ATOMIC_MOVE/REPLACE_EXISTING). Cancellation
  before publication cleans only its own temporary record. Removing the selected
  APK leaves a missing exact selection; explicit clearing is separate.
- Verification, selected-ABI preparation and fixed loading run on one IO thread
  with a two-item queue. Request cancellation interrupts future/checkpoints;
  late unpublished sessions close. No hashing/extraction happens on Binder.
- Script and preview gates remain separate while sharing the same per-kind SDK
  session. Execution rejects concurrent use instead of queuing another SDK call;
  close cancels active work and defers SDK release until the call returns.
- Recreated services can open sessions from already pinned exact code, without
  allocating a third prepared snapshot. Different artifact/ABI identities still
  require process restart, including failed initialization after code was pinned.
- Availability returns two cached records and only requests a single-flight,
  throttled asynchronous inspection. CHECKING, INSTALLED, AVAILABLE and failure
  states are distinct; safe source/loaded fields do not claim recognition success.
  Observed removal/corruption/change blocks subsequent cached resolution; no
  implicit hot replacement. During an active run, already acquired work remains
  bound to its original session; explicit startup preflight is still pending.
- Current process ABI uses 64-bit process architecture plus supported ABI checks,
  not simply the first exported/supported ABI. Export-target ABI rules are unchanged.

## Host evidence

- Resolver tests cover demand-only loading, per-kind reuse, off-caller inspection,
  failure codes reaching the actual adapter, shared ownership, changed/removed
  selections, cancelled late session cleanup, nonqueued execution and deferred
  close. Pure ABI tests reject unknown/32-bit/inconsistent architecture.
- Store tests use real publisher-signed exports. They cover import-versus-selection,
  wrong-kind rejection, replacement, missing selected APK without fallback,
  cancellation immediately before publication, malformed records and explicit clear.
- Serial host gate passed: RuntimeService 123 tests (1 existing skip), shared
  vision-components 43 tests, all 0 failures/errors. Base/OpenCV-only/ONNX-only
  service profiles each passed 123 tests with isolated output and Gradle cache.
- First compilation exposed an internal ABI helper used across modules; it is now
  an explicit shared verifier method. A repeated-selection test exposed Windows
  File.renameTo replacement semantics, so publication uses an injected platform
  atomic primitive rather than deleting the old record. An overlapping developer
  build hit a classes.jar lock and an invalid APK-layout read; serial rerun passed
  without weakening signature checks. Final checks run after those jobs terminated.
- Rust format/strict Clippy/workspace tests/schema/codegen checks passed. Catalog
  and protocol remain 175 blocks/159 APIs/protocol26; no runtime API or downloader.
- Final `build.cmd -Verify` passed in 6m27s (1075 tasks), after the final shutdown
  logging guard and storage/classifier fixes. Studio 674 tests (1 existing skip),
  RuntimeService 123 (1 existing skip), vision-components 43: 0 failures/errors.
  Lint: 0 errors, 36 warnings, 16 hints. APK/ABI/model/layout checks passed and
  MuMu 0 update installation succeeded with data retained. APK 178,334,244 bytes;
  compressed entry payload 172,375,005; layout/signing/padding 5,959,239. These are
  package observations, not provider savings or recognition performance results.

## Remaining gates

- Studio settings import/inventory/selection UI and truthful component requirement
  displays were pending at this gate; the [management follow-up](vision-management-readiness-2026-10-14.md)
  now adds local management/readiness labels. Project/function preflight remains.
- Project run/test/function preflight and explicit bounded startup preparation.
  First cold load currently shares the existing five-second recognition deadline;
  slow loading fails explicitly rather than increasing it or returning empty success.
- Loaded metadata currently means a backend/class-loader session was created,
  not successful lazy SDK/model initialization. SDK execution-failure feedback
  into cached status is implemented in that follow-up, with user-facing readiness
  distinctions. Recognition still returns a failure, never empty success, on a
  library/init error; actual initialization/recognition acceptance remains separate.
- Shared signed-fixture test inputs now explicitly include exported APK bytes
  and the configured publisher key, so a provider-only change forces validation
  reruns even if shared Kotlin tests/classes are otherwise unchanged, added by the
  follow-up. Cached tests alone were not evidence of changed-fixture validation.
- Actual PackageManager/provider/native/model loading and synthetic recognition on
  supported Android/ABI devices, recreated service sessions and code-change restart.
  The injected JVM resolver tests do not execute PathClassLoader or SDK inference.
- ARM/API24/25, workbench UI/lifecycle, standalone export and condition-separated
  performance/memory evidence remain. Default Studio still full; no private script
  run or device acceptance is inferred from build/install success.
