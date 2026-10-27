# Optional vision and standalone Runner execution plan

Date: 2026-10-13. Owner: current development thread. Status: historical staged plan, superseded by the Studio master status.

2026-10-24: this document preserves historical component-stage descriptions and
their original evidence boundaries; it is not an outstanding-task list. Current
implemented Studio/component functionality, completed performance work and
explicitly deferred standalone Runner/download work are tracked in the
[master plan](studio-first-version-master-plan.md). The newly authorized release
closeout is [ADR0151](../adr/0151-bounded-test-evidence-and-studio-release-closeout.md).

2026-10-15: current Studio development is consolidated in the [master plan](studio-first-version-master-plan.md) and [177-block matrix](studio-block-coverage.md). Stages 1–3 below are historical component stages, not the newly authorized Studio stages 1–3. No current authorization for tests, APK, installation or Runner work is implied.

Current scope update (2026-10-14): the user split out a Studio-only nine-stage development cycle, deferring test execution, APK/device acceptance and independent Runner work. Its [stage-nine closeout](../verification/studio-stage9-closeout-2026-10-14.md) and [pending acceptance checklist](studio-acceptance-checklist.md) are separate from stages 0–12 below. Completing that code cycle does not close the provider, release, performance or Runner gates here. Keep the historical evidence and its original conditions; do not treat it as authorization to install or run scripts now.

## Confirmed scope

Studio must not always embed a full Runner APK. Runner templates and optional OpenCV/OCR components are independent artifacts, acquired only when needed. This batch does not implement downloads, endpoints, automatic installation, general Chinese OCR or arbitrary external code/model loading. Missing components are an explicit unavailable state, never a substituted algorithm or a successful empty result.

`project.json.capabilities` remains the sole script authorization source. Declared, installed, enabled and supported-on-device are separate facts. Existing projects and resource data are not rewritten when a component is absent.

## Corrections to the earlier outline

- Existing `Screen.transform` (5010) already has bounded Rust rotation, mirror, grayscale and nearest-neighbor scaling. `Screen.crop`, frame ownership/release, color queries and components exist. Reuse them; do not introduce a second ImageFrame registry or aliases that duplicate the same API.
- Baseline `NativeVisionAdapter` used OpenCV even for ONNX BGR/bilinear preprocessing. It is now split into an SDK-neutral facade, gray backend and ONNX backend; independent preprocessing passed28 exact-tensor synthetic comparisons. This does not replace pending real-device/API24 acceptance or optional-component availability preflight.
- ZIP entry uncompressed sizes are not additive APK savings. Record compressed payload, native alignment and ZIP holes separately. The nested Runner also contains native libraries; do not count those twice in projected savings.
- Decompiled Java/UI registrations prove only the visible behavior or call signature. Undecompiled Native operations are not proof of algorithm semantics or performance equivalence.

## Stages and gates

| Stage | Implementation | Evidence required before completion | Status |
|---|---|---|---|
| 0 Baseline | Record existing contracts, legacy Java evidence, current dependencies and package composition; freeze exclusions | Source-linked inventory, no duplicate APIs in plan | Reviewed; see inventory |
| 1 Packaging boundary | Remove compulsory embedded Runner; independent bounded local template inventory; explicit missing-template UI; preserve signing/freeze/rollback | Base Studio contains no Runner asset; real supplied template build/sign/export regression | Default embedding removed; signed local import/inventory/selection and packaging freeze wired; SAF/device packaging acceptance remains |
| 2 Optional adapters | Separate host facade from OpenCV and ONNX implementations; four compile profiles with isolated output/cache; closed fixed dispatch table | Compile/test base, CV-only, ONNX-only, full; missing-provider rejects before native loading | Four profiles compile/test; SDK-only provider/SPI modules export. ADR0104 connects demand resolution into script and preview dispatch while retaining default bundled full behavior; actual provider/device acceptance remains |
| 3 OCR independence | BGR conversion, ROI, bilinear resize, channel normalization/padding without OpenCV; keep model hash/CTC alphabet and cancellation | Tensor golden vectors against previous OpenCV path, OCR fixture equivalence; ONNX-only has no CV classes/SO | Code and28 tensor comparisons passed; standalone model runs without CV; lowercase l/1 accuracy failure retained, ARM/API coverage pending |
| 4 Component contract | Bounded descriptors: kind/version/runtime ABI/Android ABI/minSdk/hash/size/license; local ready/missing/incompatible/corrupt states; no downloads | Wrong ABI/hash/version rejects; stale ticket/cancel/failure cleanup; no arbitrary model/class path | Private verification/preparation/lifetime, atomic exact selection, production dispatch and ADR0105 management UI are wired, with no fallback. Host tests and signing gates pass; actual SAF/SDK/provider device acceptance remains |
| 5 Project/UI availability | Show requested capability versus actual availability; settings and function details explain requirements; run/test/package preflight | Existing declarations unchanged; unavailable operation cannot silently execute; no automatic system permission grant | ADR0106 adds digest-bound emitted-operation requirements; ADR0107 wires Studio run/isolated step/configuration startup to cancellable bounded preparation and one-use source/selection receipts. Historical absence remains unknown. Function/workbench early preflight, compiled demands in standalone packaging/startup and provider/device acceptance remain; default declarations are not SDK demands |
| 6 Rust image additions | Reuse existing frame transforms; add only missing threshold/processing operations with explicit alpha/interpolation/color semantics | Schema/codegen/source roundtrip + runtime tests; borrowed source remains unchanged; memory/cancel limits | Threshold mode added to existing transform and verified; further processing operations, memory stress and device acceptance remain |
| 7 Image workbench | Shared original/processed preview, fit/100%/zoom/pan, pixel grid/ruler, precise coordinates, ROI/match/OCR overlays, bounded undo/redo | Preview and output geometry tests; overlays excluded from saved pixels; session/stale return guards | Shared workspace now reached from screenshot tools and library; original comparison, bounded history, explicit transforms/crop, fit/100%, ruler/grid and processed-image tests are implemented under ADR0101. Host golden/history tests exist; Android rendered/gesture/alpha and device acceptance remain |
| 8 Unified workflow | Source -> processing -> test -> template folder/name/save or typed block insertion; position selection and cancel restore same draft | Image/source switching, resource-budget failure, before/after/child insertion, one undo, no implicit run | Processed-image test/save and explicit processing-pipeline insertion are implemented. ADR0102 validates exact staged candidates against formal project/all drafts before one undo. ADR0103 now appends tested pixel/gray/glyph/alphanumeric tails with declared scalar outputs and final-frame release/retention. Android lifecycle/orientation/alpha and standalone-device acceptance remain; processed coordinates are not touch coordinates |
| 9 Function library | Preserve generated contracts; compact categories/detail requirements; reuse transform options, don't add cosmetic duplicates | All published operations have editable config/save/compiler/runtime/docs; invalid input doesn't save | Implemented for generated catalog and compact detail UI; contract/runtime coverage is still audited per newly published block |
| 10 Selective Runner | Real base/CV/ONNX/full template families per ABI; choose exact compatible artifact; frozen project dependency report; signing unchanged | Contents audited for missing/extra libraries/model, reproducible manifest/hash, tamper/cancel/export tests | Four development ABI families exported and audited; exact-family selection/revalidation wired; arm64-only export/device regression remains |
| 11 Package/performance evidence | Separate compressed/raw components; ROI/template/resolution/backend condition; P50/P95/P99 and peak memory | Actual measurements only, no guessed speed or size percentages; fresh ZIP validation | Host Rust matching baseline recorded; device/provider/peak-memory evidence pending |
| 12 Release regression | Android24/25 + supported ABI; overlay lifecycle, stop/reconnect/rotation/permission failures; root and script UI smoke | Local gates and explicit device acceptance records; no private script execution by default | Pending |

## Legacy behavior to adopt

Concrete reviewed source boundaries: [inventory](legacy-image-boundary-inventory.md). Bounded history observed in the decompiled canvas belongs to mask editing; it is not proof of arbitrary processing-pipeline history.

- One-key-play: crop/rotate/compress/save wrappers and color/matching functions; keep documented parameter semantics, not its shared temporary BMP files.
- Yibian: image/display coordinate conversion, unfiltered high-zoom pixel view, color magnifier, original versus processed frame and bounded history, search ROI and result overlays.
- Do not assume threshold/blur/morphology/ORB implementations are visible just because a function is registered. Each addition needs its own concrete source/contract justification.

## Execution order and persistence

0 -> 1/2 -> 3/4 -> 5 -> 6/7/8 -> 9/10 -> 11/12. Stages may share regression runs but do not call a stage done based only on a successful compile. Keep per-stage evidence and remaining items here. No downloads, AI, template library, accounts/markets/cloud, multi-touch or non-Root expansion in this goal.

## Current verification debt

Audit correction: compiling separate provider APKs does not complete runtime
component import, verification, extraction or loading. Studio's current default
still embeds OpenCV/ONNX/model (full profile). Do not report the requested
on-demand runtime split as complete until ADR0096 gates 2-5 pass. Packaging
templates are separate artifacts; even base projects need a selected Runner
template. Invalid explicit selections cannot fall back to another inventory
entry or the embedded template. ADR0104 now connects fixed selected providers to
script and preview dispatch in SDK-missing profiles; ADR0105 adds user-facing
management, ADR0106 adds compiled demand metadata and ADR0107 adds Studio startup
preparation. Standalone/function/test preflight and actual device
loading/recognition gates remain. Default full has not
been switched to base. An arm64 export is allowed from x86_64 Studio;
the target ABI is validated by the artifact validator, not Build.SUPPORTED_ABIS.

Provider strict verification evidence and its remaining gates:
[validator record](../verification/vision-component-validator-2026-10-13.md).

Private provider import/preparation and precise remaining runtime gates:
[storage record](../verification/vision-component-storage-2026-10-13.md).

The stabilization increment uses [ADR0100](../adr/0100-vision-provider-process-lifetime.md):
SDK session close cannot release process-pinned native files; failed SDK
initialization remains pinned. New snapshot ownership locks protect live
snapshots from recovery and allow recognized terminated transactions to be
reclaimed. Legacy completed snapshots without lock evidence and unknown content
remain untouched. That stabilization batch alone did not connect production
dispatch. The subsequent [runtime resolution increment](../adr/0104-runtime-fixed-vision-resolution.md)
adds demand loading and bounded cached availability, with separate user-facing
and device gates retained.

The stabilization batch passed host tests, Rust gates and the complete local
Verify build/install; see [lifetime evidence](../verification/vision-provider-lifetime-2026-10-13.md).
It is now suitable to return to the main editor/image workflow work without
claiming stages 4/5/12 or on-demand runtime delivery complete.

The subsequent shared image-workbench increment is documented with explicit host
evidence and remaining device/pipeline requirements in
[workbench verification](../verification/image-workbench-2026-10-14.md).

Processing pipeline insertion and the missing visual wrapper for existing
Screen.loadImage now have compiler/runtime host evidence and final Verify
build/install results; see [pipeline verification](../verification/image-pipeline-insertion-2026-10-14.md).
The follow-up adds tested recognition tails with exact preview identity/options,
typed output selection and explicit final-frame lifetime; see
[recognition-tail verification](../verification/image-recognition-tails-2026-10-14.md).
Actual device acceptance is still required; no claim of complete stage8 or
runtime provider delivery is made.

The subsequent runtime increment wires explicit fixed selections to both real
script and preview dispatch and replaces compile-only queries with bounded cached
status. Host profile/storage/resolver gates and the final full build/install are
recorded in [runtime resolution verification](../verification/runtime-vision-resolution-2026-10-14.md).
The follow-up [management/readiness increment](../verification/vision-management-readiness-2026-10-14.md)
adds user-facing local management and lazy SDK initialization-failure feedback.
Compiled-operation demand reporting, preflight/preparation and actual provider
device acceptance remain; the default build still includes the SDKs.

The previous editor/recording batch compiled and passed its unit tests, but development APK final layout validation rejected 59,760,409 bytes of overhead. The initial escalation review failed before deleting the user-approved oldest generated backup. A later review succeeded: only that exact backup was removed; the oversized current candidate was hash-verified/backed-up by the existing recovery script. Fresh `build.cmd -Verify` succeeded and installed on MuMu0 with app data retained. See [first-step evidence](../verification/optional-runner-boundary-2026-10-13.md). No device script acceptance is claimed.

## Implemented first substeps

Latest compiled demand metadata and explicit remaining startup gates:
[requirements evidence](../verification/compiled-vision-requirements-2026-10-14.md).

Studio startup preparation and its remaining profile/device/standalone gates:
[startup evidence](../verification/vision-startup-preparation-2026-10-14.md).

Current adapter/profile/preprocessing gates and honest OCR limitations: [batch evidence](../verification/optional-vision-adapters-2026-10-13.md). Offline template management is recorded in [component evidence](../verification/offline-runner-components-2026-10-13.md). Template selection is not runtime SDK installation.

- Studio default excludes the full Runner assets and no longer depends on Runner assembly for ordinary preBuild. Host packaging tests still use a real standalone Runner. `-Pautoscript.embedRunnerTemplate=true` remains the explicitly selected offline developer build path, not a downloader.
- Packaging preflight and UI distinguish missing template from an operation failure, before freezing or signing a project.
- Desired dependency family is derived from existing `project.json.capabilities` (base/CV/ONNX/full); four signed development template families now exist. The user imports/selects an exact family and the packager freezes/revalidates it. Imported templates are not loaded or installed into Studio.
- Package reporting separately categorizes OpenCV and ONNX per ABI, model and remaining native contents; these are raw/ZIP observations, not guessed savings.

The first current-cycle condition-separated Rust matching baseline is recorded in
[image performance baseline](../verification/image-performance-baseline-2026-10-14.md).
It is host-only evidence and does not close the device or provider performance gate.
