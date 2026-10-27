# ADR 0134 Documentation APK download

Date: 2026-10-20
Status: accepted

## Decision

The user authorized a public APK download in the existing VitePress site and deployment to their specified SSH server. Keep Vue 3 and @vue/theme unchanged. Add a home action, navigation item and download page with the actual version, minimum Android API, native ABIs, size, build time and SHA-256. Clearly label the current Debug-signed Studio full build; this does not create a production release, independent ARM build, App updater or component downloader.

Download preparation explicitly binds an APK to a verified expected digest and adjacent Gradle output metadata. Inspect the bounded ZIP directory for actual native ABIs and the full profile libraries/models. Validate staged copies and checksum files again at build time. APK signature verification remains a separate required pre-publication check; ZIP inventory alone is not signature verification. Artifact paths are generated and ignored, never Git-tracked. Without an APK, ordinary documentation builds show an unavailable state rather than a broken or invented download.

Package only the checked static output and selected APK. Deployment uses a separately inspected server target and reversible release directory; do not overwrite unrelated services or configuration. Keep addresses and credentials out of tracked files. Use session-only secret input, normal host-key verification and exact archive/APK digest checks. Failed connectivity is not deployment success. HTTPS/domain setup requires the corresponding hostname and certificate authority; do not weaken TLS or SSH verification to publish.

## Validation

Subsequent 2026-10-21 publication authorization also permits the two exact
single-ABI Release optimization candidates under ADR0135. The optional
`alternatives` array contains current architecture choices, not historical builds.
Both validation implementations and HTTP checks cover the entire flat inventory;
the original payload/count budgets and old Debug manifests remain supported.
Candidate status and fixed development certificate identity are mandatory; no
device acceptance or production-release status is implied. The user elected to
perform remote deployment themselves; freeze and hand off, do not activate remotely.

Require documentation/download tests, complete contract coverage, production build, static link checks, APK signature/digest checks and responsive preview. After SSH access is available, separately verify remote service routing, HTTP download headers/ranges, not-found behavior and remote file digests. A local package does not count as a deployed website.

## 2026-10-21 download history and homepage scope

The homepage remains documentation-first: no prominent release/version presentation, only a secondary download action; the top navigation links to `/download`. The independent download page owns current build metadata, a brief description and real downloadable history. Same semantic versions are distinguished by actual build timestamp and digest, not invented version numbers.

Extend the existing owned manifest with optional `history` (old single-release manifests remain readable). Preserve verified previous APKs and checksum files instead of deleting them on publication. At most eight builds and 448 MiB total APK payload, within the existing 512 MiB deployment archive budget; reject an over-budget update before writes and require explicit archival instead of silently pruning history. Every current/historical artifact is rechecked for identity, safe path, exact size, SHA-256, full-profile inventory and checksum; descriptions are bounded plain text rendered through Vue escaping. The deployment verifier and HTTP MIME/size/range checks cover all listed builds. Bootstrap still refuses overwriting an existing site; local preparation is not remote publication.

## 2026-10-21 stages 4-6: reversible updates and delivery freeze

Keep bootstrap separate from updates. A server-side updater only operates on the existing owned site's direct release directories and `current` symlink. Require the expected current APK digest, an exclusive nonblocking deployment lock, archive verification, bounded link-free extraction, Nginx preflight and exact HTTP manifest/APK checks. Switch the symlink atomically without rewriting Nginx, restarting services, installing packages or deleting old releases. Failed activation restores the original link and verifies it; rollback explicitly selects a retained release and uses the same checks. Read-only inspection never activates a candidate. Keep the existing private upload directory usable; no server credentials or host defaults in source.

The handoff directory contains the static archive, checksums, exact updater/bootstrap sources, a release-bound instruction file and a machine-readable delivery manifest. APK signature verification is performed before freezing this batch, separately from ZIP inventory; record the actual certificate and APK digest in verification evidence. Public release notes distinguish verified behavior from pending device coverage. No new Android build is required for documentation-only changes, and locally verified artifacts must not be described as remotely deployed.
