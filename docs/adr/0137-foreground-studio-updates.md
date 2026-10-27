# ADR 0137: Foreground Studio updates and shared site publication

Date: 2026-10-21
Status: Accepted

## Decision

The user explicitly chose automatic checking when opening Studio, followed by
in-App downloading after confirmation and Android's installation confirmation.
This supersedes ADR0136's manual-only/browser-only limit for Studio updates,
not for Runner templates, vision components, models or arbitrary downloads.

Keep the website's `downloads/release.json` as the single version/ABI/download
source, retaining current + alternatives selection and never promoting history.
Only increasing numeric versionCode is an upgrade. Publish Studio 0.1.2 / 3;
the pre-updater App still requires one manual bootstrap installation.

Check only in the foreground, with an opt-out, bounded requests and a six-hour
automatic-check interval. Manual checks bypass that interval. Failure is visible
in About but does not interrupt startup. No background worker or new service.
The user must approve downloading; installation remains an Android system flow.
Do not request install-source permission at startup or use Root to install.

Downloads are fixed same-origin manifest APK paths, reject redirects, use a
single bounded worker and an owned cache with a nonblocking lease. Enforce
256 MiB, exact byte count, SHA-256, cancellation and a five-minute deadline.
Before handing off, verify the actual APK v2 signature against the installed
Studio certificate, then actual package/version/minSdk/ABI identity. Do not
trust a manifest's certificate assertion. Revalidate before installation.
HTTP remains visibly unencrypted; matching the installed signer is independent
of transport. Unknown installation permission requires an explicit settings
action. Do not uninstall, clear projects, switch input methods or weaken Root.

Website preparation rejects version-code regression and inconsistent current
ABI options. Keep immutable APK filenames, no-cache manifest behavior and
existing size/history budgets. Generate a separately checksummed local delivery
with exact APK identities; local packaging is not remote deployment.

## Verification

Test version/ABI/URL bounds, cancellation, truncated/oversized/tampered downloads,
foreign certificates and actual APK identity, automatic-check throttling and
installer permission behavior. Run host tests, lint, Debug build and candidate
package checks separately from actual system installation/device acceptance.
Publish only checked artifacts; do not reuse older APK metadata as the new build.

### Candidate profile correction

The x86_64 candidate verification exposed an older mandatory-arm64 assumption in
the Runner and fixed-provider descriptor readers. Accept a nonempty unique subset
of the existing two supported ABIs; still require exact descriptor/archive/ELF
agreement and all family libraries. No new ABI, SDK, model or signature trust is
added. Snapshot tests use an ABI actually present in their signed fixture. The
package probe adds an explicit x86-only policy which rejects other ABI entries;
its default dual-ABI and explicit arm64-only policies remain strict.

## 2026-10-25: Compact update prompt and explicit stages

The user requested clearer update prompts and online publication. Keep the
shared compact confirmation shell, with the version, size/ABI and release notes
before concise installation and transport notices. Use one content scroller and
shared responsive actions, so the footer stays reachable in a short viewport.
Show download percentage and a separate indeterminate verification stage;
verification remains busy and cancellable, never an install-ready shortcut.
Use explicit download, permission and installation button labels. Keep the
candidate notice and unencrypted-HTTP warning, all existing signature/digest
and package checks, foreground cancellation and system installation confirmation.

Studio 0.1.7 / versionCode 8 includes this presentation adjustment and ADR0089's
save acknowledgement fix. The online eight-package history limit is reached.
The user explicitly approved recoverably archiving only the two oldest 0.1.2
history packages, through a server-terminal command they execute; the existing
HTTP API has no archive endpoint. Do not bypass budgets or pretend the new
version is online before that archive and actual publication are confirmed.

Targeted StudioUpdatePromptTest (2) and StudioUpdateDownloadTest (6) passed;
production and host-test Kotlin compilation passed. The initial compile caught
the required Compose ExperimentalLayoutApi opt-in for the wrapping About actions;
it was added and the same focused command passed. ADR0089's 18 focused save/log
tests passed earlier in this task. No broad regression or new device acceptance
was run. Local build and online publication outcomes are recorded separately.

Both 0.1.7 full-profile optimized development-signed candidates passed necessary
package checks: ARM64 36,397,922 bytes, SHA-256
`bfd6a7564d5bb367c3404c30c90cfbdaee765e96147338bfd86b3417c9602138`;
x86_64 51,651,482 bytes, SHA-256
`51e2d5f7415b84f0b353a25c8a684472a9fb0c53bf2a385c8eb778bff940d0bc`.
The server archive command was provided. After the user reported execution, the
publisher initially returned HTTP 502 while public downloads remained available.
Subsequent signed reading confirmed the service recovered, but 0.1.6 and all eight
packages, including both approved 0.1.2 entries, remained. No new APK was uploaded.
A one-off local archive delivery script avoids multiline terminal pasting; it
only moves the approved exact packages and checksums with a manifest backup and
rollback, preserves all other history, and accepts a clean already-absent state
without inventing archive evidence. Execution remains the user's server action.

Publication subsequently completed on 2026-10-25. The user supplied ARCHIVE_OK,
six remaining packages and active service, with recoverable archive
`/var/lib/zhigou-publisher/archive-0.1.2-20261025T011315Z`. A fresh signed read
confirmed exactly the original six non-archived package hashes; both local
candidate hashes were rechecked before publication. The existing signed API
confirmed the ARM64 primary and x86_64 alternative uploads for 0.1.7 / 8.
The public no-store manifest matched signed state. Both complete public APK
downloads matched the exact sizes and hashes above and the APK MIME; the
download page returned HTTP 200. The signed revision stayed unchanged through
verification. Eight packages total 351,821,236 bytes, six history entries; no
other history, server configuration or device was changed. No new device or
performance acceptance is implied by publication.

## 2026-10-25: Close-confirmation follow-up, 0.1.8 / 9

The user explicitly approved archiving the exact oldest 0.1.4 pair and publishing
0.1.8 after upgrading the service. ADR0158's live signed archive completed;
all six existing 0.1.5/0.1.6/0.1.7 packages remained unchanged. The existing
publish client verified and uploaded these frozen development-signed optimized
full/V4 candidates without rebuilding Android or installing a device:

- ARM64: 36,398,906 bytes, SHA-256
  `5862e28bb28ffdcfd883a14b2ba28935a20d24bb1ec5a93d817883ead41d766f`.
- x86_64: 51,652,466 bytes, SHA-256
  `38e025281717b3c0e2d4f5b12542641bb986acfab45fa800162b5620d36a8a89`.

Both signed uploads confirmed 0.1.8 / 9. The public manifest's raw SHA-256 matched
signed revision `4e8033878b33043c025b9cd50343a8f89122faf1093ad9d8902979568b5f198c`,
with HTTP 200, JSON and no-store; both complete public APK streams matched their
expected sizes/digests and APK MIME. `/download` returned 200 HTML. Signed
archive receipt `archive-20261025T023435Z-e19f449a9a7abed5` was complete and
contained only the two approved 0.1.4 identities; final signed revision stayed
unchanged. Eight public packages, six history entries, total 352,010,984 bytes,
within existing budget. No other history, previous manual archive, configuration
or device was changed by publication. This is delivery verification, not a new
phone or performance acceptance result.
