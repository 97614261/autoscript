# ADR 0209: Consistent visual block indentation

Date: 2026-11-06

## Decision

Repair the indentation mismatch in the retained multi-select editor. Previously its disclosure arrow, checkbox and label all moved with depth, while branch headings reserved a separate checkbox column. Group backgrounds and the selected scope rail did not reserve that column. Children could therefore appear to move left relative to a branch, even though their executable ownership was correct.

Reuse `EditorProgramTreeRow` in both the main tree and the multi-select editor. Keep the optional 20dp checkbox column before the hierarchy padding, followed by the same 16dp disclosure column and label padding used by ordinary rows. Branch headings and scope drawing receive the identical checkbox offset. Plain, expanded and folded container siblings share their label column. Keep the existing compact row heights, 12dp hierarchy step, disclosure actions, summary expansion, range selection and branch insertion anchors.

Do not clamp display levels at executable depth 32: every conditional or result branch adds a heading level. Reserve levels through 65, including empty branch headings on a depth-32 container, in row padding, guide positions and content width. Preserve the 32-scope drawing budget by retaining the nearest scopes rather than discarding all deeper scopes. Continue drawing only the nearest selected scope rail, without introducing multiple hierarchy lines.

Audit all 219 current catalog contracts. The eight container kinds are cycle, repeat, while, conditional, call-with-result, ordinary image search, gray image search and multi-result image search. Other kinds use the same ordinary row renderer. Dynamic elseif branches use the existing heading projection. No node ID, source, executable depth, schema, compiler, Root, history or runtime behavior changes.

## Validation

Focused checks cover the reported elseif layout with ordinary and folded/expanded conditional siblings; every container/slot combined with every nested container; every catalog block as a sibling; branch folding and focused-view rebasing; 32-level condition nesting and empty headings at the depth limit; fixed checkbox/disclosure/guide offsets; and existing branch, navigation, grouping and execution-marker behavior.

Release production/test source compilation passed. Of 54 focused JVM checks, 53 passed on the first run. The remaining old source-wiring check expected five row-height call sites from the duplicated implementation; the shared implementation has three. Update that expectation and rerun only that one check: it passed. No production code changed after the first run, and the other 53 checks were not repeated. All six new indentation regressions passed in the initial run. The targeted diff whitespace check passed.

Evidence is `build/editor-indent-checks.log`, preserved initial XML files in `build/editor-indent-first-results/`, the single-check follow-up in `build/editor-indent-compact-row-recheck.log`, and the catalog audit in `build/editor-indent-catalog-audit.json`. These are host-side geometry/projection/wiring checks, not device touch or visual acceptance. No Studio APK, version bump, installation, device connection, private-script execution, online publication, Git commit, backend deployment, server configuration, secret or project-backup changes occurred. Online ARM remains 0.1.39/code40 and the last x86 package remains 0.1.38.

## Subsequent publication request

The user subsequently authorized packaging and online publication. Prepare Studio 0.1.40/code41 ARM64 with the existing development signer, full vision profile and default V4. Reuse the completed checks, including the seven selection-action rechecks after ADR0209. The optimized build, packageRelease compaction and necessary package/native/model/signature checks passed: 36,736,374 bytes, SHA256 `c58fed9c5760d04b4f686a7bc9dea4efc23e8887aa16deb8a2343c5244f60523`; ZIP layout/signing/padding is 30,918 bytes.

The signed preview selected only the oldest 0.1.35 x86 history package for recoverable archival, but the server rejected the archive with `ARCHIVE_CAPACITY_EXCEEDED`: 32 retained archives, 2,771,638,560 bytes; the limit is 32 archives / 4 GiB. The existing HTTP API has no archive-retention cleanup operation. Public and WireGuard SSH maintenance probes were unreachable; the enabled in-app browser had no existing terminal tab. Ask the user to move only `archive-20261025T023435Z-e19f449a9a7abed5` from `/var/lib/zhigou-publisher/archives/` to `/var/lib/zhigou-publisher/archive-retained/`, preserving the complete archive and freeing one managed slot. This maintenance action has not yet been observed.

The signed online manifest was rechecked after the rejection and remains 0.1.39/code40 with revision `daa0bcb151e4d8c09bdbbd8a760d608061b1f8c3d7fd5c9b43ea0ce2440e36c4`. No new APK upload, server configuration/key/project-backup change, device action or Git commit occurred. Preserve `build/editor-indent-arm64-distribution.log`, `build/editor-indent-archive-selected-preview.log`, the rejected `build/editor-indent-archive.log`, archive inventory and `build/editor-indent-online-blocked.log`. Publication remains pending capacity maintenance; package preparation is not publication.

## Revised development retention

The user explicitly replaced recoverable APK retention with deletion of all older download packages and APK archives, keeping only the newest package. This does not include project backups, keys, scripts or website sources. Prepare the one-off `build/delivery/keep-latest-apk.sh` administrator command. It stops only the original publisher, takes its process lock, verifies the complete live manifest, preflights completed managed/retained archives and the earlier flat manual APK archives, and refuses unknown files, incomplete transactions and links. Reuse the original history-archive transaction before deleting its verified obsolete payload, retaining the current primary. The shell restores the original publisher even on failure.

Six isolated maintenance checks passed, including 32 full archive slots, current-package preservation and repeat execution, valid retained/manual archives, unknown-file protection and incomplete-archive rejection. No Android rebuild or repeated acceptance suite is needed. The prepared release verifier now requires exactly one latest ARM package and no managed APK archives.

The HTTP API still has no delete operation, and no server shell is available. The maintenance command has not run on the server. Run it first to clear capacity, publish the already built 0.1.40 through the unchanged signed HTTP client, then run the same saved command again to remove the replaced 0.1.39 and finish latest-only retention. Online publication and actual cleanup remain pending; no server configuration, key, project-backup or device changes have occurred.

## Actual publication and cleanup

The user reported the first maintenance run completed. Authenticated reading confirmed exactly one 0.1.39 package and zero managed archives. Publish the already built 0.1.40 ARM64 package through the original `publish-apk.cmd` client; no rebuild or new x86 package. The signed manifest confirmed publication. Public no-store manifest bytes matched the signed revision; the complete ARM APK matched 36,736,374 bytes and SHA256 `c58fed9c5760d04b4f686a7bc9dea4efc23e8887aa16deb8a2343c5244f60523`, with correct APK MIME, checksum, byte-range support and download page HTTP 200.

The original publisher temporarily retained 0.1.39 as history: revision `55be5a7c31188bacd195be90b3dbab620d7f0cd088ecb2708fe700ac0051565a`, two packages / 73,472,912 bytes. The user then reported the second cleanup run completed. A bounded final check, without downloading the APK again, confirmed signed/public manifest agreement, only 0.1.40/code41 ARM64 / 36,736,374 bytes, zero managed archives / zero bytes, and HTTP 404 for the replaced 0.1.39 APK and checksum. Final revision: `5bad78a5ebf24a2bc0460eba621fe5cdc91019d73006c2adc8bcb173ba6905de`.

Evidence: `build/editor-indent-after-user-cleanup.json`, `build/editor-indent-publish-arm64.log`, `build/editor-indent-public-verify.log`, `build/editor-indent-cleanup-verify.log` and `build/editor-indent-online-final.json`. The administrator cleanup was executed by the user, not through an unavailable HTTP delete operation or remote shell. No device installation, project-script execution, repeated acceptance suite, Git commit, Root change, server configuration/key/project-backup change, backend deployment or documentation-source deployment occurred. Publication checks do not constitute device UI acceptance.
