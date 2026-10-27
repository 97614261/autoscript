# ADR 0157: Final acceptance and packaging for the editor closeout

Status: accepted, 2026-10-24.

The user approved the third and final batch (items 12-13): one centralized
regression pass for ADR0155/0156, repair actual failures, then package a candidate.
This supersedes those batches' compilation-only limits. Do not start another
feature or optimization programme, redesign the sidebar/bottom toolbar, change
full/V4, or attribute old performance results to this candidate.

Add focused regressions for typed replacement, grouped history, multi-field batch
arguments, checkpoint ownership, log cancellation and updater queue cancellation.
Run the Studio and affected shared-module host suites and lint centrally. If a
failure is found, keep its evidence and rerun only the affected tests first;
recompile/repackage when the repaired sources require it. Compilation or static
source assertions do not substitute for behavioral or Android interaction tests.

MuMu acceptance requires a saved/idle instance and explicit permission for the
candidate installation/restart. Use only newly owned public QA projects, preserve
existing data and input method, and do not run private scripts or use a phone.
Verify the exact installed candidate digest. Do not restart an emulator, force GC,
change system input settings or suppress a failing test to manufacture a pass.
Report any unsupported or untested device boundary as such.

The user subsequently confirmed the saved state and authorized installing the
candidate on MuMu 0 and restarting Studio for this acceptance. Preserve all app
data and the input method; this does not authorize phone actions or publication.

Package Studio with the existing development signing identity and full visual
configuration. Keep output profiles isolated and use the existing package,
signature, ABI/model and layout checks. Do not delete unknown files or old user
data. No website publication, update-manifest change, version bump or final
installation is authorized by the generic packaging request. Deliver the local
candidate paths and distinguish Debug acceptance from an optimized Release build.

Completed on 2026-10-24. The centralized development-profile host run covered
1,386 tests: 1,384 passed, no failures/errors, two pre-existing conditional skips
(RunnerComponentStore exported matrix; Windows symbolic-link adapter case).
Studio lint completed with zero errors, 37 warnings and 12 hints. The compiler
module has no JVM tests; its native draft compiler was exercised on MuMu below.
Old field/log source assertions were updated to the accepted behavior; new
behavioral checks cover typed replacement, grouped history, numeric/boolean batch
changes, journal ownership, log cancellation and queued update cancellation.

The new Android fixture initially failed test compilation on nullable entry-flow
handling and the accessibility action constant; both fixture issues were fixed
before execution. No production regression required another implementation change.
One exact-candidate MuMu 0 / x86_64 / API32 / 720x1280 run passed all 14 cases:
shared search/navigation/session retention; replacement rejection retaining input,
real Binder compilation and grouped undo/redo; multi-field batch compilation and
single undo; discard confirmation; constrained-density/large-font actions; live
mutable logs and 1,024-row history filtering/channel switches; log full-screen;
and the real overlay image-library/workbench return paths. The short viewport was
a local composition density, not a system rotation or keyboard acceptance.
Owned QA project/log cleanup succeeded. No scripts ran and no IME/system settings,
private projects, model defaults, server files or version metadata changed.

The accepted local candidate is Studio 0.1.5 / 6, development Debug/full, both
arm64-v8a and x86_64, existing development certificate. APK:
`android/apps/studio-android/build/outputs/apk/debug/studio-android-debug.apk`.
SHA-256: `760ad1bcd2f826a690536d74c1209d79ef1a0c40064eb74016d96d77dc321483`.
Size: 92,182,122 bytes (87.91 MiB); ZIP overhead after one packaging compaction:
36,334 bytes. Signature, 16KiB alignment, native ABI/dependency/symbol checks and
both fixed-model digests passed before the data-preserving MuMu installation.
The installed digest matched; the test APK digest was
`439b42bfdd5919d005de6b8b5e1038d7721e1a82f7d774b5f005aa0b7f0d79dc`.
Generated raw device evidence is `build/editor-final-acceptance/first-device-run.txt`.
This closes items 12-13 and the agreed three-batch editor work. It does not claim
a new optimized Release, phone/API24/keyboard/long-run performance acceptance or
website publication; previous device/performance results remain historical.

## Subsequent explicit online update, 2026-10-25

After the profile/About simplification and its data-preserving MuMu 0 Debug
installation, the user explicitly requested an online update. This supersedes
the earlier no-publication/no-version-bump scope for this request. Increment
Studio to 0.1.6/versionCode 7 so installed code-6 clients can detect the update.
Keep the development certificate, full/V4, history budget and existing updater
verification; do not connect a phone or change the server configuration.

Both isolated optimized Release candidates passed signature, actual identity,
native/model contents, alignment and compact-ZIP checks:

- ARM64: 36,396,446 bytes; SHA-256
  `9b309acae621cd9b5ebb04133234fea0777553f4bcd34ba2d571d830b3ecf12d`.
- x86_64: 51,650,006 bytes; SHA-256
  `1e4ca0dc3c3983d90ea00df96a5599552918fac07d213cc1fae873ff6ef2b8f9`.

The existing bidirectional-HMAC publisher confirmed both uploads. The public
no-store update manifest lists code 7 as the primary ARM64 and alternative
x86_64. Both actual public downloads matched the full local SHA-256, byte count
and APK MIME; the download page returned HTTP 200, and the signed revision was
unchanged after verification. Eight packages total 351,474,996 bytes, including
six retained history entries; nothing was deleted. No server configuration,
private project or device was changed by publication. No new full regression,
Release interaction, native ARM/API24 or performance acceptance is claimed.

## Subsequent save/update prompt patch, 2026-10-25

Studio 0.1.7 / 8 was published after the user recoverably archived only the
approved oldest 0.1.2 pair. It includes ADR0089's save acknowledgement fix and
ADR0137's compact update prompt. ARM64 is 36,397,922 bytes (SHA-256
`bfd6a7564d5bb367c3404c30c90cfbdaee765e96147338bfd86b3417c9602138`);
x86_64 is 51,651,482 bytes (SHA-256
`51e2d5f7415b84f0b353a25c8a684472a9fb0c53bf2a385c8eb778bff940d0bc`).
Both signed publications and full public downloads were verified, including
no-store manifest agreement, correct size/hash/MIME and download-page HTTP 200.
There are eight packages totaling 351,821,236 bytes and six retained history
entries. No server configuration or device changed, and no old acceptance
result is promoted to a new device or performance result.
