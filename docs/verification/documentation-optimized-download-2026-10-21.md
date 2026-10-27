# Optimized Studio downloads and self-deployment handoff

Date: 2026-10-21
Scope: user-authorized documentation/download update; user performs remote deployment.

## Changes and publication boundary

- Keep VitePress, Vue 3 and `@vue/theme`, documentation-first home and dedicated download navigation.
- ARM64 is the primary current download, x86_64 is a current `alternatives` entry, not history. Keep the latest dual-ABI Debug APK in history.
- Both new files are development-certificate-signed Release optimization candidates, explicitly device-unverified. No installation or production-release claim.
- Retain full vision, default V4 and optional V5; no Android rebuild, model switch, updater or component downloader in this task.
- Node staging, archive packaging, Python disk verification and HTTP manifest/size/MIME/range verification cover all three APKs. Old Debug/single-release manifests remain supported.
- Responsive check found the theme's 320px body minimum overflowing a viewport with a scrollbar. The shared `html body` minimum-width override removes it; filenames/checksums wrap without clipping.

## Exact files

| Role | ABI | Bytes | SHA-256 |
| --- | --- | --- | --- |
| Current optimized candidate | arm64-v8a | 65,225,132 | `4f54027eb01472d526f20d0231e2efc2c5d577b034244a67f4b5342ee0993b7b` |
| Current optimized alternative | x86_64 | 103,139,076 | `9750cfdbc9193d32cee2eb828fface008efcfef2834f553e28017499a429d8a9` |
| Historical Debug | arm64-v8a / x86_64 | 194,688,195 | `54ebff69c084e5c0efa1d0313475d9e0344bfaa78bfff54b8dfc4ba08ccbfc69` |

Combined APK payload: 363,052,403 bytes, within 448 MiB / 8-file budget.

Re-ran Build Tools 37.0.0 `apksigner` and `aapt` checks on both candidates: APK v2, one signer, expected application identity, API24 minimum/API36 target, non-debuggable. Certificate SHA-256:

```text
8e48ea72d827f96ea57c6b7bf4d566bac7581fa23106324bf3296fa9e2024f6c
```

## Recoverable archival

The user explicitly approved moving historical `72b3b16437b86bd2d23421163ae73da1d5f0d3d71dcb243f40d7bd0b2c4b08ec` out of the next public list. The existing archival tool preserved APK, checksum and original manifest under ignored `build/docs-site-download-archive/<full digest>/` before removing the owned public copies. No server release was deleted or modified.

## Verification

- Node regression: 35 passed, zero failed/skipped. First sandbox run could not perform temporary renames/junctions; rerun under normal permissions passed. One new test fixture initially changed versionName without its digest-bound URL; corrected the fixture to test versionCode mismatch instead.
- Linux/WSL deployment safety: 9 passed; updater: 20 passed, zero skips. Includes explicit HTTP checks for primary, alternative and history, stale identities, archive constraints, locks and rollback.
- Production build: 200 APIs / 21 groups, 217 HTML pages, 47,776 checked internal links/assets.
- Browser checked desktop, 375px and 320px, architecture links, expandable file identity and checksum wrapping. Final 320px document scroll/client widths both 305px; no horizontal overflow. Temporary viewport overrides reset.
- Frozen archive validated by the included Python updater in a fresh Linux temporary releases directory; all handoff checksums and APK payloads verified. No Nginx activation invoked. An earlier verification harness omitted the temporary `releases` parent; corrected the harness, not the production updater.

## Final handoff

Use only `build/docs-site-deploy/20261021T125612Z-4f54027eb014/`, not intermediate build directories.

- Archive: `zhigou-docs-20261021T125612Z-4f54027eb014.tar.gz`
- Archive bytes: 177,868,569
- Archive SHA-256: `57d58a66b863bcab797b12c740994304fb09c8e2419aa0d776fb868a72b9cde7`
- Seven handoff files: archive, archive checksum, bootstrap, updater, delivery manifest, all-files checksum and `DEPLOY.md`.
- No credentials, server address, private projects or QA data in the site/delivery.

The existing public manifest was read successfully and still identified primary `72b3b16437b8`. SSH timed out; the user then elected self-deployment. No upload or remote activation occurred. On the server, run the frozen `update-static.py --check` first, then update bound to its exact old release/digest; retain all old server directories. `CHECK_OK` alone is read-only, only `UPDATE_OK` means local activation checks passed. Public download and HTTPS checks remain separate.
