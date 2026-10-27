# Manual update and Root compatibility verification

Date: 2026-10-21
Decision: [ADR 0136](../adr/0136-manual-studio-update-and-root-diagnostics.md)

## Scope and conclusion

Implemented manual Studio update checking, bounded independent App diagnostics,
and cancellable screenshot preparation for the original runtime session. Studio
now requests package-manager native-library extraction so the existing Root
controller can prefer the installed daemon over an app-writable extracted copy.

This addresses a concrete compatibility risk. The user's report that x86 works
does not establish the exact ARM phone cause: ABI, Android version, Root manager
and device security policy differ. No phone screenshot acceptance is claimed.

## Final verification

- `build.cmd -Verify`: passed; final log is
  `build/manual-update-root-final-verify.log` (ignored local output).
- Nine Debug test modules: 1,303 total, 1,301 passed, two skipped,
  zero failures and zero errors. Includes update manifest/URL/ABI/version checks,
  diagnostic persistence/redaction and runtime readiness/cancellation coverage.
- Debug lint: zero errors, 35 warnings and 11 informational findings.
- Repository layout and native packaging checks passed.
- Debug 0.1.1 / versionCode 2 installed over the existing MuMu 0 App;
  application data retained. Read-only package inspection confirmed x86_64.
  No private scripts were run and no simulator data was cleared.
- No screenshot UI/device acceptance was performed in this batch. The user's
  x86 success report is separate from these build and installation results.
- `build-distribution.cmd -Profile arm64 -Verify`: passed; final log is
  `build/manual-update-root-arm64-verify.log` (ignored local output).
- ARM optimized build passed host verification, Release lint, R8, signing,
  alignment, ABI/model/Root-entry and package-size gates. Release lint has zero
  errors, 27 warnings and 11 informational findings. Studio Release tests have
  883 total, one skipped and zero failures/errors.

The initial build exposed a Gradle `java` name-shadowing issue and a test helper
that accepted too narrow an exception type; both were corrected before the final
successful runs. A temporary update-specific network policy was removed before
the final builds to preserve the existing script HTTP behavior.

## Final artifacts

| Artifact | Size | SHA-256 |
| --- | ---: | --- |
| Debug, arm64-v8a + x86_64 | 96,134,479 bytes | `6460d45d828148a0f6665a0b81ef2d9561ed8b163978e89a6abf843867e28589` |
| Optimized ARM64 candidate | 36,208,296 bytes (34.53 MiB) | `cc4f1e0297321b71d5ce1f34815aff04be76a75a5c7d4fad054ae5ebd05e84e9` |

Local output locations, deliberately not checked-in binaries:

```text
android/apps/studio-android/build/outputs/apk/debug/studio-android-debug.apk
build/profiles/distribution/arm64/apps/studio-android/outputs/apk/release/studio-android-release.apk
```

ARM package inspection confirmed `extractNativeLibs=true`, the existing
`usesCleartextTraffic=true`, and no `networkSecurityConfig`. Default full vision,
V4 and optional V5 model digests are unchanged. Native compression reduces APK
transfer size but may increase installed extracted-library storage. ZIP layout,
signing and alignment overhead is 0.029 MiB.

Both candidates use the existing development certificate, not a production key.
The ARM APK has one APK v2 signer with certificate SHA-256:

```text
8e48ea72d827f96ea57c6b7bf4d566bac7581fa23106324bf3296fa9e2024f6c
```

## Outstanding evidence

- The previously provided phone endpoint timed out with connection error 10060.
  No ARM installation, Root-ready result or actual screenshot was obtained.
- A reachable phone must establish the real startup stage and capture result.
  Review the fixed codes in My / Runtime environment / Root and screenshot
  diagnostics; logs also use the `ZhigouDiagnostics` logcat tag.
- No Root/security policy, input method or device authorization was changed.
- The existing public download manifest was still 0.1.0 / versionCode 1 during
  the read-only check. This batch did not regenerate or publish site downloads.
  The new App therefore correctly treats that server release as older.
- Android's installer remains the installation/signature boundary. HTTP update
  metadata is explicitly unencrypted and is not an authenticity assertion.

See the [usage and diagnostic guide](../guides/manual-app-update-and-root-diagnostics.md).
