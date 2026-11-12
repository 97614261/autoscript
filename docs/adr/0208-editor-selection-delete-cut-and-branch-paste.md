# ADR 0208: Editor selection actions and direct branch paste

Date: 2026-11-06

## Decision

Repair the retained visual editor's bottom delete/cut actions and allow pasting directly into a selected condition or recognition branch. Keep the existing compact toolbar and draft/history transactions.

The previous delete and command callbacks used references to local functions capturing selection or branch state. Their generated Kotlin FunctionReferenceImpl instances compare by callable identity without comparing those captured arguments. Equal toolbar descriptors or remembered Compose lambdas could therefore retain an earlier callback. Use ordinary capturing lambdas, with a small explicit delete-action factory, so changed context replaces the event handler.

Freeze deletion against the flow, exact source and selected node identities. Count descendants once even when both a container and its child are checked. Folding and other presentation changes do not invalidate this request; actual source, selection or flow changes still do. Both the upper and lower delete buttons use the same action.

Permit paste while a branch heading is selected. Paste directly into that exact slot, appending after existing children in clipboard order. Ordinary node selections retain before/after/inside choices. Freeze the selected branch as part of the paste request and verify its owner/source/slot, so switching branches on the same owner cannot redirect an existing request. This includes then/else/elseif and recognition/call success/failure slots.

Multi-cut uses the existing whole-forest copy/delete operation, coalescing selected descendants under selected ancestors and retaining a single undo boundary. Only replace the clipboard after a successful cut; a failed or empty cut preserves the original source and clipboard. Status text reports the actual copied/cut node count. No executable node identity, persisted schema, compiler, Root or runtime behavior changes.

## Validation

Release production/test source compilation and 17 focused JVM checks passed in one run: seven selection-action regressions, six existing continuous-editing checks, two toolbar checks and two existing paste-before regressions. The new checks cover callback rebinding between multi-selections with unchanged title/enabled state; folded-container deletion and stale source/flow/selection rejection; noncontiguous multi-cut, ancestor deduplication and single undo/redo; empty-cut preservation; ordered forest paste into then, two elseif and else slots; stale same-owner branch changes; and recognition success/failure paste. Evidence: `build/editor-selection-actions-checks.log` and the four selected test result XML files under the existing distribution/x86_64 output. The targeted diff whitespace check passed.

These are host-side callback/model regressions and source compilation, not actual device touch/UI acceptance. No Studio APK build, device connection/installation, private-script execution, version bump, publication, Git commit, server configuration, secret or project-backup changes occurred. Online Studio remains 0.1.38/code39.

## Subsequent ARM-only publication

On 2026-11-06 the user requested online publication and explicitly allowed updating ARM only. Studio 0.1.39/code40 ARM64 is now published through the original mutually signed HTTP flow. Reuse the 17 focused checks; the original development signer, full vision profile, default V4, optimized distribution build, packageRelease compaction and necessary package/signature/native/model checks passed. No new x86 package was built or published.

- ARM64: 36,736,538 bytes; SHA256 `9626f9d0b904062706e3aefaeb7dc88dfda8a1d36b89660737eb9f7a90bfb66a`.
- Final manifest revision: `daa0bcb151e4d8c09bdbbd8a760d608061b1f8c3d7fd5c9b43ea0ce2440e36c4`; eight packages totaling 354,793,024 bytes.

After signed preview, only the oldest 0.1.35 ARM64 package was recoverably archived under the user's standing authorization to `archive-20261106T014806Z-9342fdcd764e0a48`. All previous x86 packages, including 0.1.38 now in history under the original publication contract, and all other retained package metadata were verified intact. Existing archives remain.

The public no-store manifest digest matches the signed revision. Complete ARM download size/hash/MIME, Range, checksum file and the download page passed. The first additional retained-history check falsely compared string timestamps with DateTime values because it used generic PowerShell JSON parsing on the saved snapshot; the existing string-preserving protocol parser corrected this verification script. Only history was rechecked, without repeating the already-passed APK download. Preserve the first failure note and evidence in `build/editor-selection-online-history-first-failure.log`, `build/editor-selection-online-final.log` and `build/editor-selection-online-history-final.log`; build/archive/publication evidence uses the same `build/editor-selection-*` prefix.

No device installation, private-script execution, repeated full tests, Git commit, Root behavior change, server configuration/secret/project-backup change or backend/documentation deployment occurred. Publication does not establish device UI acceptance.

## Recheck after the indentation change

On 2026-11-06 the user repeated the three selection-action reports after ADR0209. Verify that the capturing delete action, direct branch paste and whole-selection cut paths are still present, then rerun only `EditorSelectionActionsTest`: all seven focused regressions passed on the current shared-row code. Evidence: `build/editor-selection-after-indent-checks.log` and its result XML. No further production change, package or device action occurred in this recheck. ARM 0.1.39 already contains ADR0208; the last published x86 package is 0.1.38. The user's installed version has been requested and is not yet confirmed; host regressions do not rule out an actual device issue.
