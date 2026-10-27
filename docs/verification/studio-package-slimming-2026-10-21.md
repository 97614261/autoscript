# Studio package slimming: 2026-10-21

Scope and safety decisions: [ADR0135](../adr/0135-studio-distribution-size-profiles.md).
Local candidate compilation and verification are complete. No APK was installed,
published to the website, or uploaded. No application/project data was changed.

## Final artifacts

All candidates retain `com.zhigou.kaifaban`, version 0.1.0/code 1, minSdk 24 and
targetSdk 36. Release optimization is enabled, but the signer is the existing
Android development certificate, not a production release certificate.

| Artifact | Bytes | MiB | Reduction against retained dual-ABI Debug |
| --- | ---: | ---: | ---: |
| Retained development/full Debug | 194,688,195 | 185.67 | Baseline |
| ARM64 distribution/full Release candidate | 65,225,132 | 62.20 | 66.50% |
| x86_64 distribution/full Release candidate | 103,139,076 | 98.36 | 47.02% |

These reductions combine ABI separation, R8/resource shrinking, native optimization
and compact packaging. They are **not** same-ABI optimizer-only percentages.

Paths, relative to the repository:

- Baseline: `android/apps/studio-android/build/outputs/apk/debug/studio-android-debug.apk`
- ARM: `build/profiles/distribution/arm64/apps/studio-android/outputs/apk/release/studio-android-release.apk`
- x86: `build/profiles/distribution/x86_64/apps/studio-android/outputs/apk/release/studio-android-release.apk`
- Each candidate profile has `size-report.json`, `signature.txt`, `badging.txt`,
  `native-packaging-audit/verification.txt`, and R8 evidence under
  `apps/studio-android/outputs/mapping/release/`.

Exact APK SHA-256:

```text
baseline 54ebff69c084e5c0efa1d0313475d9e0344bfaa78bfff54b8dfc4ba08ccbfc69
arm64    4f54027eb01472d526f20d0231e2efc2c5d577b034244a67f4b5342ee0993b7b
x86_64   9750cfdbc9193d32cee2eb828fface008efcfef2834f553e28017499a429d8a9
```

## Size accounting

Final compressed-entry payload is 65,131,100 bytes for ARM and 103,043,524 bytes
for x86. ZIP headers, alignment, signing and other layout space total 94,032 and
95,552 bytes respectively, below the separate 2 MiB distribution gate. The old
baseline has 12,973,423 bytes of combined layout/signing/alignment/unused space;
this is not described as entirely removable padding.

Final ARM breakdown, as bytes actually stored in the APK:

| Part | Bytes |
| --- | ---: |
| ARM native libraries | 47,247,152 |
| Both fixed OCR models | 14,334,154 |
| DEX | 2,987,089 |
| Resources | 533,245 |
| Other entries | 29,460 |
| Layout/signing/alignment | 94,032 |

The ARM engine and Studio compiler libraries are 4,378,576 and 2,675,624 bytes.
Default Debug equivalents remain 10,038,288 and 5,386,168 bytes. No package was
manually recompressed after signing. The full OpenCV/ONNX libraries, both fixed
OCR models, notices and licenses remain present. No Runner APK is embedded.

## Implemented safeguards

- Isolated ABI/profile outputs and exact Gradle project-cache checks; ordinary
  `build.cmd`/`build-mobile.cmd` retain their existing behavior.
- Explicit development signing, unchanged signer against the retained baseline,
  APK v2 verification, 16 KiB ZIP alignment and non-debuggable Release manifest.
- R8 keeps the native bridge, Root entry points, native callbacks, external-provider
  SPI, OpenCV/ONNX JNI-facing Java classes and persisted Gson model identities.
- Added the previously missing `onNativeRootDisconnected` keep rule. Native
  dispatch, cancellation and cleanup protocols were not changed.
- Optimized Rust uses level 3/thin LTO/one codegen unit with overflow checks,
  debug assertions and unwind retained; stripped APKs retain dynamic exports.
- Default vision remains full and default OCR remains V4. V4/V5 exact model hashes
  match the fixed production inventory. No downloader or optional-component
  default change was introduced.
- Size reports bind exact APK/R8 evidence hashes. Missing native libraries/models,
  foreign ABI entries, embedded Runner, unsafe/duplicate ZIP names and excessive
  layout space fail closed. Reports cannot overwrite their APK inputs.

## Verification

- Final end-to-end ARM `-Verify` and x86 builds both exit 0 with final size,
  native/model, signer, manifest, alignment and R8 gates passed.
- Android Release host tests: 1,280 cases, 0 failures/errors, 2 existing skips
  (1,278 executed/passed), across the eight modules with test results. The compiler
  module test task has no test sources and is not counted as additional cases.
- Release Lint: 0 errors, 25 warnings and 11 hints; not represented as warning-free.
- Rust distribution-profile tests: 303 passed, 0 failed, 11 ignored across
  pixel-vision, glyph-ocr, runtime-executor and flow-compiler. Zero-case doc-test
  invocations are not added to the count.
- APK accounting/inventory/R8 fixtures: 12 tests passed.
- Signature/manifest text fixtures: 15 cases passed, covering old and Build Tools
  37 output, malformed/ambiguous data, wrong identity/SDK and debuggable manifests.
- PowerShell parser accepts the four scripts; Python compilation, Rust formatting
  and repository link/layout checks pass. The Windows `.cmd` entry was exercised
  with `-SkipBuild` and passed the same candidate gates.

Evidence logs under ignored `build/`:

```text
slimming-arm64-e2e-final.log
slimming-x86-e2e-final.log
slimming-rust-tests.log
slimming-rust-format.log
slimming-command-entry.log
```

Earlier attempts and their logs are retained separately: the first manually typed
Gradle properties were split by PowerShell; later candidate gates correctly
refused unrecognized Build Tools 37 signer/minSdk labels. Strict parsing was fixed
and covered by fixtures, not bypassed. Preliminary 62.00/98.16 MiB candidates lacked
the final blanket SDK JNI keep protection and are not the artifacts listed here.

## Remaining acceptance boundaries

No MuMu, ARM device, API 24 device, runtime recognition accuracy or runtime latency
acceptance was performed for these optimized APKs. Host tests and byte-level
verification do not replace that acceptance. The previously installed APK and
website downloads still refer to their original, unchanged bytes. A new optimized
universal package was not built; its command is available but it has no measured
size or acceptance claim in this report.
