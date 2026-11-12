# ADR 0207: Shared scalar value inputs and calculation insertion

Date: 2026-11-05

## Decision

Use one compact value picker for supported scalar input bindings. Its variable list offers a conversion/calculation action that reuses the existing bounded calculation editor without an output target. Literal fields keep their existing fixed-value path. Output destinations, image handles and resource identities remain named bindings.

The calculation operation chooser groups shortcuts under common operations, assignment/conversion, arithmetic/min/max, rounding/remainder, text, splitting, and list/object reads. Search spans groups. Only the chosen operation's operands are displayed; operands can themselves use typed calculated sources. Preserve complex expressions when switching modes. Source-only editing hides destination increments and supplies a valid conversion fallback.

From variable management, completing a formula now opens the existing insertion-position chooser before any variable save or source mutation. Above, below, list bottom and explicit child branches reuse the original insertion transaction. Replacement is unavailable in this route. Cancelling preserves the formula and variable draft. Confirmation rechecks the exact project, flow, source and selection before asynchronous saving; insertion retains capability, undo and stale-context protection.

## Input representation and execution

- Retain the existing string fields: plain names keep their original meaning; an internal `=expression` prefix represents a calculated input. The UI hides this protocol prefix. No intermediate nodes, hidden project declarations, or new runtime opcode are added.
- A compiler allowlist distinguishes input roles from destinations. Supported existing inputs include loop counts/durations/waits/checks, comparison operands, output text values, copy sources, utility/data/file operands, clipboard/floating text, UI assignment, rectangle image-search edges, and mapped X/Y coordinates. Image handles and structured recognition-result bindings retain their original selection semantics. Polygon coordinates remain fixed and cannot be combined with dynamic rectangle bounds.
- Parse and infer every expression with the existing typed AST and current flow parameters/local/global scope. Keep the 1024-byte, 128-node, 16-depth expression budgets and existing result/string/collection limits. Reject wrong types, inaccessible or image variables, output expressions, unknown functions and injected Lua. Collection calls retain the existing capability requirements.
- Require Runtime API 1.8 for calculated inputs. Saving and both single/all unsaved-draft validation paths promote through the existing mechanism; validation does not write drafts to disk.
- Compile distinct sources once per flow into closures over current variables. Read them through that flow's existing variable getter at the actual input read. Flows without calculated sources keep the original getter. Repeat counts and total time limits keep entry-time evaluation; waits/checks evaluate at their execution point, and while conditions see updated variables on each comparison.
- Preserve generated identity headers at byte zero, runtime bounds/cancellation, image provenance, source maps and visual error locations. No screenshot-frame caching or Root/input behavior change is introduced.

Store renaming tokenizes expressions and preserves quoted literals and function names. Maintenance warnings and project variable searches extract actual references instead of treating the encoded source as one identifier. Read-only summaries display expression text without evaluating it. Image-to-image copy still uses the direct variable selector.

133 node schemas expose 260 input-field occurrences as identifier-or-expression unions. A JSON parse and static role audit confirm each union matches an allowed native input role, with destinations/handles unchanged. This is not a full independent JSON Schema semantic-validation claim.

## Validation

Focused checks passed: six compiler tests, one JNI temporary-project save/draft/compile/engine round-trip, seven scalar-input JVM tests, nine insertion tests, four variable-maintenance tests, one existing floating-dialog test and one ProjectStore save/reopen/rename test. Existing shortcut, loop and calculation checks also passed during the initial targeted build. Tests cover dynamic loop reads, duration units, real RuntimeExecutor utility/data/copy reads, errors with visual rows, invalid scope/types/handles/destinations, nested conversions, operation categories/search, maintenance summaries, clipboard/floating sources and image-coordinate input forms.

The mapped-point compiler test injects a fixed Lua host function to observe evaluated X/Y values; it does not claim real image-coordinate mapping or device capture acceptance. Existing compiler loop fixtures similarly use their original bounded host stubs. The JNI round-trip uses the actual EngineSession; utility/collection execution uses the real RuntimeExecutor.

Release production/unit-test compilation passed with the existing distribution/x86_64 configuration. Evidence includes `build/value-source-rust-tests-fixed.log`, `build/value-source-bridge-tests.log`, `build/value-source-android-checks.log`, `build/value-source-android-final.log`, `build/value-source-image-inputs-runtime-final.log`, `build/value-source-image-inputs-android.log`, and the schema audit/JVM result JSON files. Earlier failed fixture/build/audit attempts are retained where logged; they are not counted as successful checks.

No Studio APK, version change, device connection/installation, private-script execution, publication, Git commit, server/secret/project-backup change, or account-backend deployment is part of this batch. Documentation changes are local source only. Online Studio remains 0.1.37/code38; host checks do not constitute device layout, keyboard, or ARM acceptance.

## Subsequent release authorization and outcome

On 2026-11-05 the user requested packaging and online publication for personal testing. Studio 0.1.38/code39 is now published through the existing mutually signed HTTP flow. The 29 focused checks were reused without repeating the full suite. Both optimized distribution builds retain the original development signer, full vision profile and default V4; the original packageRelease compaction and necessary package/signature/native/model checks passed.

- ARM64: 36,737,126 bytes; SHA256 `7003c181c0babbfd0030502c277b499d6bd7fa1cab14e11dc7405420be9dcdac`.
- x86_64: 51,998,102 bytes; SHA256 `b134d504a5035dc43d1b5f9eb90f413dcfabe5d14eca73ba6011cfdf4b4cd18c`.
- Final manifest revision: `67c89b024fa5142a845a544bb541a3cc10da58083226b9957eafb820ea23bf67`; eight packages totaling 354,756,676 bytes.

Under the user's standing authorization, signed preview preceded recoverable archiving of only the oldest 0.1.34 pair to `archive-20261105T114552Z-7933f426b01357f0`. All retained history and existing archives remain. The public no-store manifest digest matches the signed revision; complete downloads of both APKs match local sizes and hashes, and MIME, Range, checksum files and the download page passed. Evidence is in `build/value-source-{arm64,x86}-distribution.log`, `build/value-source-archive-preview.log`, `build/value-source-archive.log`, `build/value-source-publish-{arm64,x86}.log` and `build/value-source-online-final.log`.

No device installation, private-script execution, Git commit, Root behavior change, server configuration/secret/project-backup change, account-backend deployment or separate documentation-source deployment occurred. Publication checks do not constitute device UI, keyboard, or ARM functional acceptance.
