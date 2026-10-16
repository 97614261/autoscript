# Vision provider SPI: 2026-10-13

## Implemented source boundary

- Added `android:vision-spi`, a small SDK-neutral module containing the borrowed-buffer contract, bounded rectangle/buffer validation, cancellation work handle, model source interface and alphanumeric decoder.
- Moved the existing ONNX preprocessing and common vision ownership helpers into that SPI; the runtime service still owns session/lifecycle orchestration and fixed JNI dispatch.
- Added separate application modules for fixed OpenCV and ONNX provider entries. Their Gradle script copies only the corresponding existing source set and assets, adds only its SDK dependency, generates a signed-in descriptor and exports a separate development APK. No Root daemon, engine, Studio compiler, project data or script API is included by design.
- Provider entries expose fixed classes only; they do not accept script-supplied class names, native paths or arbitrary model files. The ONNX entry requires a host-owned `NativeVisionModelSource`, and the recognizer still checks the pinned model size/hash.
- Studio packaging checks the exact selected artifact (including base), not an
  arbitrary healthy inventory item. No-selection and an invalid explicit
  selection are separate; only no-selection may use the explicit embedded full
  fallback. Export target ABIs are verified in the APK, not gated by the current
  Studio device ABI. Packaging metadata does not prove runtime SDK availability.
- Added SPI unit tests for buffer/rectangle/decoder bounds and added the new modules to repository layout checks.

## Verification state

The Rust runtime-executor image and host-boundary regression suite passes
offline: 94 tests passed, including transform alpha/source preservation,
component bounds, PNG geometry, cancellation and frame ownership tests.

The prior production build before this source boundary change passed 645 tests, Lint, Debug packaging and MuMu0 installation. The earlier Gradle failure was environment-specific: sandboxed execution could not reconnect to the daemon over `localhost`. With the existing Gradle cache and a permitted local build process, the provider modules now compile and export successfully.

The provider build initially exposed two source/configuration defects and both were fixed:

- the shared applied script used Android/Kotlin type-safe DSL imports unavailable to an applied script;
- moving the Android block omitted the generated descriptor asset from the provider APKs.

The corrected build passes `vision-spi` and `runtime-service` unit tests, then exports both development provider APKs. APK v2 signatures, fixed descriptors, required ABI/native entries, licenses and the pinned ONNX model hash were checked successfully. The subsequent [shared validator batch](vision-component-validator-2026-10-13.md) adds exact native/asset allowlists and signed rejection tests. Actual Android manifest parsing, private import/loading and Android24/25 acceptance remain unfinished. No provider APK was installed into Studio.

Follow-up verification on 2026-10-13:

Packaging/installer correction batch:

- The five `RunnerCapabilityAvailabilityTest` tests passed: base needs a
  template, exact selection is checked, unrelated corrupt artifacts are not
  attributed to a missing family, embedded fallback cannot override an invalid
  explicit selection, and arm64 export does not require an arm64 packager.
- `scripts/build-install-mumu-test.ps1` passed, including registry discovery on
  a custom drive, spaces, explicit path precedence and owned-process timeout.
- `build.cmd -Verify` passed and automatically found MuMu from its installation
  record. The new APK (`175,910,822` bytes) was updated on instance 0 without
  clearing app data. Studio reports: 650 tests, 0 failures, 0 errors, 1 skipped;
  the skipped test is not counted as passed.
- An initial retry using `JAVA_TOOL_OPTIONS` interfered with the log-lease test's
  exact helper output (`READY`) by inserting a JVM startup diagnostic. The
  successful rerun instead used a process-local `GRADLE_OPTS` system property:
  `-Dorg.gradle.jvmargs="-Xmx3g -XX:TieredStopAtLevel=1 -Dfile.encoding=UTF-8"`.
  Daemon logs confirmed the JIT option; no permanent JVM setting was changed.

- `:android:vision-spi:testDebugUnitTest` and `:android:runtime-service:testDebugUnitTest`: passed.
- `:apps:vision-opencv-component:exportVisionComponent` and
  `:apps:vision-onnx-component:exportVisionComponent`: passed.
- `:apps:studio-android:testDebugUnitTest` and
  `:apps:studio-android:assembleDebug`: passed (637 actionable tasks).
- Full `build.cmd -Verify` passed with `GRADLE_OPTS=-XX:TieredStopAtLevel=1`:
  tests, lint, Debug APK packaging, repository layout and component packaging all
  passed (`894` actionable tasks; APK `175,911,800` bytes). The first full run
  hit a Java 17 C2 compiler crash during lint; the JIT-limited rerun completed.
  This first installation attempt failed to discover MuMu; registry inspection
  later located `D:\soft\MuMuPlayer\nx_main\MuMuManager.exe`. The same APK was
  successfully updated on MuMu 0 (`127.0.0.1:16384`) using `-SkipBuild` and the
  explicit manager path; application data was retained. The installer now
  searches bounded registry-derived candidate paths without a recursive scan.

Completed build gate (not a pending cache-recovery task):

```powershell
.\gradlew.bat :android:vision-spi:testDebugUnitTest :android:runtime-service:testDebugUnitTest `
  :apps:vision-opencv-component:exportVisionComponent `
  :apps:vision-onnx-component:exportVisionComponent --offline --console=plain
```

Next gates: Android acceptance for the shared manifest reader, private
import/extraction and the fixed provider loader, then synthetic device fixtures.
Runtime loading, Android24/25 acceptance and default Studio SDK omission remain
unfinished. Installation of the full-profile Studio is not provider loading
acceptance.
