# ADR 0135: Non-destructive Studio package slimming

Date: 2026-10-21
Status: Accepted for candidate builds; device and production-release acceptance separate.

## Decision

The user approved package slimming without removing functionality. Keep the existing
development/Debug workflow and its artifacts. Add an explicit `distribution` native
profile and isolated ARM64, x86_64 and universal candidate outputs. This authorizes
ARM candidate compilation for size measurement, not installation or publication.

- Default vision remains `full`, default OCR remains V4, and the fixed V5 candidate
  remains bundled. Do not remove algorithms, models, licenses or capabilities.
- `build-distribution.cmd` builds Studio Release with R8/resource shrinking and
  optimized Rust. It explicitly opts into the existing development signing key;
  these are development-signed candidates, not production-signed releases.
- Native distribution compilation uses optimization level 3, thin LTO and one
  codegen unit, retaining overflow checks, debug assertions and unwind behavior.
  Unstripped diagnostics stay outside APKs. Do not use size-oriented optimization
  for the vision hot path or change native interfaces.
- Distribution outputs and Gradle project caches live under
  `build/profiles/distribution/<abi-profile>`. Cargo's named profile is separate
  from existing debug outputs. Non-default ABI profiles require their exact cache
  directory. Default development behavior is unchanged.
- Re-run only the final Release packaging task for compact ZIP output; never
  modify/recompress an already signed APK or delete prior packages to save space.
- Preserve JNI callbacks, Root class names, provider SPI and persisted Gson DTO
  names/fields. R8 success is not proof that a candidate works on a device.
- Verify signatures, exact ABI inventory, native exports/debug stripping, both
  fixed model hashes and license presence. Report compressed entry sizes separately
  from signing/alignment/layout overhead. Do not count all overhead as reclaimable.
- No device installation, website download replacement, component downloader,
  optional-model replacement, Runner redesign or application-data migration.

## Subsequent website publication authorization

On 2026-10-21 the user explicitly requested updating and deploying the site after
the slimming report. This authorizes publishing the exact verified ARM64 and x86_64
APKs as development-signed, device-unverified optimization candidates. It does not
authorize installation or claim device acceptance. Keep the full visual profile,
default V4 and existing static-site atomic-update/rollback boundaries. Current ABI
alternatives are distinct from historical builds; both validators verify every file.
The 8-file/448 MiB public payload budget remains. Archiving a selected old public
download requires explicit approval and keeps recoverable files outside the site.

## Validation

Use synthetic packaging fixtures, isolated candidate builds, existing host unit
tests/lint and byte-level inventories. Record exact candidate hashes and sizes in
the dated verification report; track device acceptance separately. A 100 MiB ARM
goal is a measurement target, not permission to silently drop functionality.
