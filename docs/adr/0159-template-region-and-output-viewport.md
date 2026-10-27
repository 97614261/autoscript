# ADR 0159: Screenshot template region hints and reachable output forms

Status: accepted, 2026-10-25.

## Resource Usage Lookup Follow-Up

Expose a read-only image usage report from the same bounded scanner used by
resource deletion protection. Include saved plugins, changed editor drafts,
template-directory references, UI image controls, image recipes and conservative
historical Lua text matches. Keep saved references until explicitly saved away;
do not rewrite scripts, automatically remove references or offer force deletion.
Invalid or over-budget input fails visibly instead of reporting an unused image.
Cap displayed results while counting all matches, with a bounded scan deadline.
Deletion refusal opens a shared prompt-layer report; other failures also open a
visible prompt rather than relying on the library's small status text. Lookup
freezes all current editor drafts on Main and scans on IO, without a persistent
index or raw script logging. This follow-up does not authorize online publishing.

Local verification: six scanner tests and five focused resource-store tests pass
with zero failures/errors/skips. Cases cover saved/draft identity and deduplication,
directory boundaries, UI/recipe owners, historical Lua, invalid/deep/oversized
data, cancellation, capped single-node and cross-node reports, shared deletion
guards, corrupted recipes and transactional unreferenced deletion. Studio main
and acceptance-test Kotlin compilation pass. The final ARM Debug build is
57,251,299 bytes, SHA-256
`d1dda987b464f3ec5b3ea7a761f7bd6477ef4fb145574d19d7948b62fdb4ccf9`.
The image-library instrumentation includes report and refusal cases, but was not
run: the reachable ARM virtual system had an active editor service. Do not
overwrite/restart that session or reuse the earlier candidate's UI results as
verification of this candidate. No installation or online publication occurred.

The user subsequently requested overwrite installation. The same final ARM
Debug candidate above was installed with `adb install -r` on the user-designated
API25 arm64 virtual Android target; installation returned Success. PackageManager
confirmed com.zhigou.kaifaban, version 0.1.9 / 10 and the new update time, and
MainActivity was opened. No uninstall/data clearing, script execution, online
publication or additional UI acceptance was performed.

## Image Library Follow-Up

The image library now exposes a compact delete action per image with a shared
confirmation prompt. It reuses ProjectStore's resource transaction and conflict
checks; it does not delete files directly. The editor freezes its current plugin
drafts before the IO operation. Saved sources, unsaved drafts, template-directory
references, UI image fields and image recipes protect their resources. Invalid
draft JSON blocks deletion rather than assuming that it contains no references.
Search, folder choice and unrelated resources are preserved after deletion.

The user approved a data-preserving ARM test installation and rotation of owned
QA pages. The API25 ARM virtual system passed three focused store tests and eight
real library/window cases, including delete cancellation and confirmation. The
same candidate also passed 18 fresh-frame/template cases: portrait, landscape
1280x720, and restored portrait, with two distinct public frame contents and both
normal execution and isolated node-subtree execution. Matching used the exact
recorded ROI and 1000-permille similarity, with no tolerance increase or scaling.
Candidate SHA-256: `82b6066c069569f050b4e89c35779047ebcd33aa2704d52c04e082ba84c2c4f6`.

A second, focused 18-case pass used a deterministic multicolor 19x19 tile rather
than the larger two-color crop, through the same production capture, PNG save,
region-default and compile/runtime paths. Both suites passed with owned-project
cleanup and orientation restoration; no keyboard settings changed. The second
test APK SHA-256 was
`60b0c845c75175db4075dce0fa515952b0f77f9b316894a66e38b7ea5b38b292`.
The reported PNG itself is 19x19, fully opaque, and carries the expected
1280x720 source bounds. None of these facts proves that its original scene still
contains matching pixels.

The subsequent same-scene capture at the reported 1280x720 geometry confirmed
the icon at its original location. With tolerance zero, zero of its 361 pixels
were equal; tolerance 20 would match all pixels, but was not applied to the user's
script. Two current captures were identical at that location. The old template
was approximately 7.5 percent darker. This evidence narrowed the failure to
color contamination, rather than coordinates or a missing icon.

An owned native-dialog test then reproduced `Dialog.hide` leaving the separate
WindowManager dim layer in a Root capture after the editor's old 200ms delay.
The flat surface's expected `ffe0a060` became `ffc99056` at that delay, and
returned to its exact original color after settling. The failing candidate was
`82b6066c069569f050b4e89c35779047ebcd33aa2704d52c04e082ba84c2c4f6`,
with test APK `78d0782811420ec2b9b19b499085fc276c19a3a4da4b34690987c4c78543bc52`;
cleanup succeeded. This failed reproduction is retained separately from retests.

Manual editor capture now suppresses native session windows synchronously,
waits two bounded main-choreographer frames, then allows the dim layer to settle
with a 500ms floor and bounded system animation-scale allowance. No animation
settings are modified. Cancellation/closing is propagated, and only the matching
capture suppression key is released; it cannot clear a newer request. This
changes manual template/point capture, not script screenshot or matching speed.
Existing contaminated PNGs are not modified or automatically brightened: the
original unoccluded scene must be captured again. Do not silently lower thresholds,
enlarge bounds, rotate templates or claim a script rerun occurred. No online
publication is authorized by this follow-up.

The capture-barrier policy's two focused JVM checks passed, and the ARM Debug
candidate and its test APK built successfully. Candidate SHA-256:
`d0026d4ec3fbe93d88ffef9e4047afcd9c61a1e9ab82d33f2298a0dfb759c67a`;
test APK SHA-256:
`fa67ec7ec1bb2212487f1c01e4d1c83621eb960dd5f9b17fab75d81ee26e23ea`.
The first installation attempt was blocked by the specified LAN device becoming
offline. Building these artifacts is not a successful installation or a passed
post-fix device test; the latter must be recorded separately when connectivity
is restored.

Connectivity later recovered on the user's specified LAN endpoint. The same
candidate and test APK were installed successfully with `-r`, preserving data.
On this API25 ARM virtual system, all four focused native-dim cases passed:
the post-barrier capture was exactly `ffe0a060`, equal to both the original
surface and the later settled capture. A same-candidate 18-case 19x19 multicolor
template pass then confirmed original-ROI exact matching in portrait, landscape
1280x720 and restored portrait, using ordinary and isolated node-subtree runs.
Both reports recorded successful owned-project cleanup; orientation was restored
and the input method was not changed. The temporary USB loopback diagnostic
forward had already been removed. These results are not native/API24 acceptance
or an execution of the user's game script. The old darkened resource and all
user project sources remain unchanged; a fresh template is needed for that case.

## Decision

Screenshot selection templates retain their original full-screen pixel bounds
and source frame dimensions in one versioned PNG `tEXt` chunk, inserted directly
after IHDR while encoding the temporary PNG. Pixels and the project/resource
manifest remain unchanged. The hint travels with the existing image resource and
its backups, without a second metadata transaction or a runtime API change.
Only the direct full-screen screenshot save path writes the hint. Re-encoded
workbench/processed images do not inherit screen provenance.

Read only this fixed uncompressed ASCII key, before IDAT, with CRC, IHDR, integer,
geometry, chunk count and byte limits. Missing, unsupported, damaged or mismatched
hints are ignored as optional editor defaults, not proof of current screen size.
Do not infer a region from template dimensions alone, rescale coordinates, read
arbitrary PNG metadata, or change recognition algorithms or runtime coordinates.

New recognition forms may follow a selected template's recorded region. Multiple
templates use the union only when every selected image has a valid hint from the
same source dimensions. An explicit region/coordinate-variable edit or existing
node's region takes precedence. An explicit template-region action can opt back
in. Asynchronous reads are bound to the exact selected paths and form generation;
a late result must not overwrite manual inputs or a different template selection.
Derived initial regions update the close-check baseline, while an independent
original fallback remains available for templates without hints. Merely opening
a form must not create a discard warning; deliberate template/parameter edits
still do.

The output form uses adaptive four/two/one-column type choices, an independently
scrollable input area with a nonzero natural text-editor height, and fixed commit
actions. Related pickers bound their actual viewport rather than reserving a
fixed-height list that can displace the footer. Preserve close/save baselines,
variable/expression semantics and the shared overlay/keyboard-inset behavior.

## Scope And Verification

Review related image, variable, loop and condition forms for the same short-
viewport issue and fix demonstrated layout defects, without redesigning unrelated
pages. Run focused metadata/default-policy and output/layout regressions and a
Kotlin compile. No APK packaging, installation, device campaign, version bump or
online publication is implied by this request.

Confirmed related defects include generic function/condition long forms,
image bounds and template-selection forms, output/image/loop pickers, the
calculation picker's non-scrolling scope column, and fixed-height image rows
that clipped font-scaled controls. Reuse the shared bounded panel and keyboard
inset handling, with scrolling bodies and reachable commit/cancel actions.

Local verification: Studio main/test Kotlin compilation and 68 tests across
ScreenshotTemplateRegionTest, ImageRecognitionWorkflowTest,
VisualImageRecognitionDialogTest, StudioPanelGeometryTest, DebugOutputCodeTest
and PopupStyleDraftTest pass, with zero failures/errors/skips. Coverage includes
lossless PNG bytes/pixels, bounded malformed-metadata rejection, template-source
selection/union/default precedence, adaptive choice geometry and existing
save/discard guards. Scoped whitespace checks pass. These are not actual-device
landscape/keyboard measurements or a full Lint/APK/device/publishing campaign.

## Subsequent Publication Request

The user subsequently requested an online update. Build Studio 0.1.9 /
versionCode 10 as the existing development-signed optimized ARM64 and x86_64
full/V4 candidates, retaining the required package/signature checks and signed
HTTP publication contract. Reuse the focused 68-test result; do not install a
device or repeat unrelated acceptance campaigns. The public eight-package
inventory is full. Any selected history archiving requires separate explicit
approval, and local APK generation is not proof of live publication.

The user explicitly approved only the 0.1.5 ARM64/x86_64 history pair. At signed
revision `4e8033878b33043c025b9cd50343a8f89122faf1093ad9d8902979568b5f198c`,
the existing API confirmed recoverable private archive
`archive-20261025T073000Z-087e97842098e4b7`, containing only SHA-256 identities
`03106295f22a83271bc6ffb1575c18109d29e3d960e1d8c26a9034e4ed604b62` and
`a769de3782f2acbca303d6e1494adbc8d137384a284c79f84dd9eeb00715c230`.
The signed client verified that current 0.1.8 packages and all retained
0.1.6/0.1.7 metadata stayed unchanged. Earlier archives were not touched.

## Live Publication Outcome

Both optimized full/V4 development-signed 0.1.9 / 10 candidates passed the
existing ABI/model/native-alignment/compact-layout and actual signature/manifest
checks. Main packaging was rerun to avoid incremental ZIP gaps; no native/model
semantics or dependencies were removed. The exact artifacts are:

- ARM64: 36,404,998 bytes; SHA-256
  `953269d5d1ef511f8766c6d1f9b365dd84cdc0106602e4336ffe08a6d77112b3`.
- x86_64: 51,658,558 bytes; SHA-256
  `2a01c55c01243d4e03013fbb20ed081ee5a4e075a03612db0ced07ac38ce1f5e`.

The existing HMAC API confirmed the ARM64 primary and x86_64 alternative
publications. The raw public no-store JSON matched signed revision
`870db0aa0e4d8eda6bde42dbf266ddb78dc714c224284601d57398e76d64f7b8`;
both complete public APK streams matched size, digest and APK MIME, and the
download page returned HTTP 200 HTML. A final signed read confirmed the same
revision, eight packages / 352,210,784 bytes, and exactly the six retained
0.1.6/0.1.7/0.1.8 hashes. App foreground/manual checks and the runtime download
page consume this shared manifest; no static page or service redeployment was
needed. This was online delivery verification, not device installation, UI
acceptance or a performance campaign.
