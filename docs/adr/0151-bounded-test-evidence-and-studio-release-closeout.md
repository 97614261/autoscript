# ADR 0151: Bounded packaging-test evidence and Studio release closeout

Status: accepted and implemented, 2026-10-24; dual-architecture publication verified.

## Scope

The user authorized the four-item closeout and publication of the resulting
App update. Preserve the current full/V4 backend, development signing identity,
project data and existing runtime semantics. AI remains visible but unopened.
No phone connection, IME changes, private script execution or standalone Runner
development is part of this batch.

## Decisions

Packaging unit tests must exercise real APK rebuild/signature/manifest/ABI and
tamper/cancellation checks in their owned temporary directories, then release
those directories in finally. They must not retain another large APK on every
successful test. Retain two fixed, small, version-marked reports with sizes,
SHA-256 and independent verifier results; also print them into the ordinary
test report. Refuse redirected report paths, unexpected existing report content
and oversize reports. Updating these known report slots does not authorize
deleting arbitrary files, old APK backups or user projects.

Any one-time cleanup of historical generated test APKs must first identify
literal regular files inside the known test output directory and validate their
paired historical verification records, size and independent verifier markers.
Preserve the records and unknown/unpaired files; never recursively clean a
workspace, build tree or temporary root. Deleted test APKs are regenerable,
not recoverable user backups.

Update the master plan to distinguish completed performance work from genuine
remaining device/accuracy boundaries. Freeze Studio 0.1.5/versionCode 6 and run
focused editor, workflow, prompt-reuse and shared-window regressions on the
new candidate. Prior endurance evidence remains tied to its original APK.

Build isolated ARM64 and x86_64 optimized Release candidates signed with the
existing development certificate. Validate actual signatures, package identity,
native/model contents and ZIP layout before publication. MuMu-only tests do not
become native ARM acceptance. Use the existing bidirectional-HMAC publisher,
its revision precondition and unchanged eight-package/448 MiB history budget.
Do not remove online history or weaken authentication to publish. Verify the
active manifest and actual downloaded bytes after publication; no success claim
until both architectures are active. App install remains user-confirmed.

## Verification

The focused gate exposed one intermittent rapid stop/start/rebind position wait.
Inspection also found that a singleton listener let callbacks queued before
unbind affect a later binding to the same service. Each connection now owns a
fresh state listener; invalidation and pending-position clearing share the inbox
lock, and main-thread state delivery checks exact listener identity. This does
not change the protocol, run/generation gates or replay the stopped operation.
The workflow test injects both an already-queued old terminal state and stale
state/position callbacks after reconnect; neither may clear or replace the
current run. Preserve the original timeout separately from the diagnostic pass.

A later input case reached the stopped scheduler state while the public state
still awaited Root cleanup. Audit found a publication race: request_stop set the
Root worker's consume-once flag before VisionHub marked cleanup pending. An
already-awake worker could consume and finish that flag, followed by the caller
setting pending again with no remaining work. Publish the flag only after the
existing priority fence and pending marker are established, in both stop and
shutdown. The engine stop flag still precedes this work; no ordinary queue,
extra wait, replay, timeout increase or false cleanup confirmation is introduced.
Cover the consume-before-publication interleaving with a deterministic host test
and retain authenticated cleanup/failure fencing in actual device checks.

The final Debug candidate SHA-256 is
`1eeea51e77b3e342755e6de7e7818cb0a411e65a786b698d4a819f22373de840`;
the test APK is
`23a2df830b1dcea8c3eebc300f34d408b497e819794f3aca1beb49e6ee0749f1`.
MuMu 0/API32 passed editor 9, workflow 30, experience 23, lifecycle 1 and
prompt reuse 7 cases (70 total), each with owned-data cleanup. Evidence is under
`build/verification/release-closeout-015-stop-final/`. The new stale-listener
injection is part of the workflow case, not a separately inflated case count.
All earlier failures and the diagnostic-only pass remain in separate directories.

The normal Verify build, tests, Lint and package/layout checks passed. JNI and
Root-client host tests passed 50 + 33 cases, including two new stop-order tests;
strict Clippy and formatting checks passed. Website tests passed 42 cases and
VitePress checked 201 API pages/221 HTML pages. These results are not a new
endurance or native ARM acceptance claim.

Historical cleanup removed only 284 preflighted generated test APKs totaling
47,827,424,698 bytes. Paired verifier records and unknown files remain; deleted
APKs are regenerable, not user backups. Repeated packaging tests now retain only
the two small fixed report slots per isolated build profile.

Both isolated optimized candidates passed Release tests, Lint, independent APK
signature/package identity, native/model contents, alignment and compact ZIP
checks. They retain the development signing certificate and full/V4 backend:

| Architecture | Bytes | SHA-256 |
|---|---:|---|
| arm64-v8a | 36,305,098 | `03106295f22a83271bc6ffb1575c18109d29e3d960e1d8c26a9034e4ed604b62` |
| x86_64 | 51,558,658 | `a769de3782f2acbca303d6e1494adbc8d137384a284c79f84dd9eeb00715c230` |

The exact x86_64 optimized APK was installed over MuMu without clearing data and
passed five framework-only Java smoke cases: installed digest/version, launch,
project toolbar, profile/documents open/close and return home. Its test APK is
`ca92ce4b6d33d717d83ab853f5c628e97ec93c44939ba076a254cbd528a0f964`.
The initial missing-runner registration, test Kotlin dependency crash and
accessibility-observation setup failure are retained separately. An allowlisted
test-only Gradle property selects the smoke runner; the default full runner is
unchanged. Smoke uses the same temporary UiAutomation flags as the existing QA,
not persistent accessibility or system-setting changes. No production artifact
changed while fixing the smoke harness. These five cases do not substitute for
the separate 70-case Debug gate or native ARM acceptance. Final Debug Lint with
the Java test source also passed, and repository layout/link verification passed
715 local links with no local-only references.

Both APKs were published through the existing bidirectional-HMAC endpoint.
The active primary ARM64 and alternative x86_64 are now Studio 0.1.5/code 6.
The independently fetched public update manifest matches the signed manifest;
actual public APK downloads match both local sizes and full hashes, with correct
APK MIME. The download page serves its live no-store manifest client. Six online
packages totaling 263,428,544 bytes remain within eight/448 MiB; no history was
deleted and no SSH/Nginx configuration or private project was changed.
Evidence: `build/verification/release-closeout-015-online/verified.json`, the
two publication logs and `release-closeout-015-release-final-smoke.log`.
They remain explicitly development-signed optimized candidates, not a production
certificate release or a claim that this batch covered native ARM/API24.
