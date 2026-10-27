# Studio code optimization items 1-7 (2026-10-18)

## Current delivery closeout 2026 10 19

The current candidate supersedes the earlier candidates listed below. It removes
the unreachable duplicate editor from ProjectPage under
[ADR0126](../adr/0126-single-studio-editor-route.md). ProjectPage is reduced from
3100 to 1383 lines; its old editor project state had no non-null assignment. Edit,
restore and capture handoff now use only EditorOverlayService/VisualProjectScreen.
The active shared editor, combined draft transactions, source toolchain, recording,
image dialogs and runtime contracts are retained. Formal project launch still
opens the designed configuration first, or compiles and starts the saved project
directly when no interface exists. UI designer, settings, packaging and backup
remain real project-list routes. No user data or source file was deleted.

`StudioRunEntryTest` now guards the single editor route and formal launch wiring,
including the capture handoff and retained designer/package/history entries. The
generated 177-kind matrix links this ledger rather than claiming all executed
tests are pending or all parameters have passed.

Latest `build-mobile.cmd -Verify`: successful in 2m38s, 1029 tasks, 18 executed.
JUnit XML totals:

| Module | Tests | Skipped | Failures / errors |
|---|---:|---:|---:|
| Studio | 793 | 1 | 0 / 0 |
| Script UI | 54 | 0 | 0 / 0 |
| Project store | 83 | 0 | 0 / 0 |
| Runtime client | 31 | 0 | 0 / 0 |
| Runtime service | 150 | 1 | 0 / 0 |
| Total | 1111 | 2 | 0 / 0 |

1109 tests passed. The skips are the explicitly opt-in Runner export matrix and
the host symbolic-link test when the OS disallows creating the test link; neither
is recorded as passed. Studio Lint and arm64 APK/native/layout checks passed;
existing Lint/deprecation warnings remain. The eliminated floating-editor symbols
are absent from the compiled ProjectPage classes. Build log:
`build/studio-code-closeout-2026-10-19.log` (local generated artifact).

The matching arm64 `assembleDebugAndroidTest` also passed (29s, 333 tasks).
The test APK is ready under the same profile; it has not been installed or run
on the disconnected endpoint. Its local build log is
`build/studio-code-closeout-androidtest-2026-10-19.log`.

Current Debug-signed full-vision arm64 APK:

- `build/profiles/arm64/apps/studio-android/outputs/apk/debug/studio-android-debug.apk`
- 87,331,182 bytes; SHA-256
  `45ac8b948926f7c0158ea55b78285d04dc26f7c536e84bacecb68fed3ea1945b`.
- ZIP payload 77,504,672 bytes; layout/signing/padding 9,826,510 bytes, below the
  unchanged 16MiB overhead gate. Original production OCR digest is unchanged.
- Candidate record: `build/studio-code-closeout-candidate-2026-10-19.json`.

The specified network endpoint remains unreachable (`connect` timed out; device
inventory empty). This APK has not been installed or device-tested. Multi-plugin,
complex UI/keyboard/orientation and performance sampling remain device acceptance
work, not newly claimed code omissions. Earlier nine-case and control-ball results
below apply only to their exact predecessor APK. No new performance percentile
was obtained, no private script run, and no debugging authorization reset.

OCR `l/1` and related ambiguity remains an unresolved accuracy limitation. The
already evaluated fixed v5 candidate was worse overall (80/150 exact versus
105/150 for v4 in the public synthetic set), so no production model or strategy
is replaced. See [OCR evaluation](../reference/ocr-strategy-evaluation-2026-10-15.md).
Independent Runner expansion, downloads, accounts/cloud and flow template library
remain deferred. This is code/documentation closeout, not full device or OCR release
acceptance, and no Git commit is created implicitly.

## Original optimization scope

This pass continues the code-only optimization plan while intentionally excluding the device/OCR
acceptance items 10 and 11. It does not change the Lua contract, vision thresholds, model, or
device behavior.

## Changes

1. Root capture chunk requests reuse one 16-byte request buffer across the transfer loop. The
   destination pixel buffer and protocol validation remain unchanged.
2. ONNX session initialization now builds the padded decode dictionary once per session instead of
   allocating `listOf("") + dictionary + listOf(" ")` for every inference.
3. OpenCV template fingerprints use a fixed lowercase hexadecimal encoder instead of `Formatter`
   based formatting on every cache lookup. Cache bounds and the SHA-256 key remain unchanged.
4. Runtime log batching, bounded queues, terminal cleanup and separate channels were already in
   place and were retained; no second writer or unbounded queue was introduced.
5. Project summaries and Flow identity indexes already use bounded stamp/content caches; this
   pass leaves their invalidation and stale-source checks intact.
6. Studio project and visual-tree lists already use stable project/node keys; no UI behavior change
   was needed for this item.
7. Optional vision profiles remain separate; no downloader, model replacement, Runner expansion,
   or device acceptance was added.

## Gates

- Rust workspace tests and formatting remain required.
- Android `project-store`, `runtime-service` tests and Studio Kotlin compilation remain required.
- MuMu, ARM64, API24, and OCR model acceptance are explicitly outside this pass.

## Editor runtime control handoff on 2026 10 19

The ARM64 follow-up fixes the previously observed editor and runtime control balls being
visible together. `EditorRuntimeControlSession` suppresses the runtime control surface on
editor entry and reconnect, and hands it back only when closing the same running or paused
Runner session. The service owns the final handoff after the editor views have been removed;
there is no asynchronous hide that can arrive after close. Negative, nonzero session
generations remain valid.

Hiding the runtime control ball now removes only that control surface, preserving persistent
and timed script prompts. Already queued normal-control show requests check the suppression
flag before adding a window. Explicit camera mode remains a separate user-triggered surface.

Local validation completed with `build-mobile.cmd -Verify`:

- Studio: 792 tests, 1 skipped, no failures or errors. This includes seven ownership-state
  cases, six project-identity cases and the editor/service wiring regression.
- Shared script UI: 54 tests; project store: 83; runtime client: 31; runtime service: 150,
  including one skipped test and the control/prompt separation regression. No failures or errors.
- Studio Lint, ARM64 Debug build, native packaging verification and repository layout check
  passed. The production OCR model and the default full vision profile are unchanged.
- Candidate: `build/profiles/arm64/apps/studio-android/outputs/apk/debug/studio-android-debug.apk`,
  87,331,182 bytes, SHA-256
  `645ffbe192e7bc74b44818bb54d71cb1815fe26759b33aeb193c33cd41442957`.

The requested endpoint `192.168.2.112:6556` recovered authorization and the prior candidate
with SHA-256 `6a49765718264790c654c205af78d0d8f87ca56bab700394cb036f1cc20fa8bd`
was installed successfully, preserving data. That endpoint reports API 25, arm64-v8a and
720x1280. The separate USB transport reported API 37 and was not used as a substitute.

On that installed candidate, the own-project `editorControlOnly=true` scenario passed real
touch expansion/collapse, existing-run suppression, prompt preservation without reissuing the
prompt, minimize/restore, paused close, reopen and stop cleanup. All owned test data was cleaned.
API25 reported stale ViewRoot origins after native window movement; QA now translates only
equal-sized roots belonging to the target package against the observed native window, waits
for stable geometry and still requires real touch and subsequent state/UI assertions. This does
not change production UI coordinates or add script execution delays.

The full Studio suite passed its first nine cases: expanded entry, maximize/restore, stable
More menu, minimize/restore, idle selected-node single step, execution highlight and pause/step/
resume, stopping while minimized, closing both editor surfaces, and real Root tap plus fresh
capture of the changed owned surface. It then failed `QA_DRAFT_COMPILE_FAILED` in multi-plugin
draft validation; this is not a complete-suite pass.

## Project global numeric identity correction

That failure exposed the short-ID generator's plugin-local allocation conflicting with the
compiler's existing project-global node/block uniqueness checks. The correction follows
[ADR 0123](../adr/0123-compact-generated-identities.md): shared session high-water marks cover
formal plugins, live drafts and empty root declarations. Insertion, subtree copying, source
parsing and recovery share the allocator; whole-plugin creation/copying use all project IDs.
Undo/canceled previews do not recycle session allocations. Existing UUIDs are unchanged, and
the full compiler/stale-context gates are retained.

Six identity tests cover cross-plugin drafts and blocks, copy/paste, recovery/undo high-water
marks, source preview allocation, empty roots and invalid/exhausted allocation. Store tests add
global initial-node uniqueness, empty-plugin creation/copy and disjoint whole-plugin node IDs.
The final full local validation totals 1110 tests: 1108 passed, 2 skipped, no failures/errors;
Studio Lint, ARM64 APK/native/layout checks and the matching Android test APK build passed.

Before the identity-corrected candidate could be installed, the network endpoint became offline
and then disappeared; the USB transport also disappeared. The final candidate listed above has
not been installed. Multi-plugin device revalidation, the remaining workflow/designer/dialog/
keyboard/orientation cases and performance sampling remain pending on the specified endpoint.
The performance invocation failed before starting any test (`device offline`), so there are no
new device P50/P95/P99 results from this attempt. No private script was executed, no app data or
debugging authorization was cleared, and no production OCR model was changed.

Temporary root-level Gradle recovery caches were preserved under `android/.gradle/`; they are
not source files and no longer pollute the repository root or Git status.
