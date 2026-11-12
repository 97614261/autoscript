# ADR 0215: Image workspace convenience and actual UI checks

## Scope

The user requested priorities 1–3: exercise the image workflow on MuMu, reduce
repeated configuration, and explain recognition results more clearly. The
published baseline is Studio 0.1.42/code43. This request does not authorize another
publication, version bump, server change or Git commit.

## Changes

- Keep raw match settings, OCR settings/region and insertion variable selections
  within one open workspace. Retain unfinished numeric inputs rather than silently
  replacing them with defaults. A different workspace/project starts fresh.
- OCR bounds survive returning to the same source and selection. A new image or
  newly selected region takes precedence. The identity token contains no pixels.
- A newly loaded recipe and the explicit saved-glyph handoff take precedence over
  the corresponding older draft.
- Never retain preview jobs, replies or recognition proof in the draft holder.
  Configuration changes and reopening require a fresh test; stale runtime replies
  cannot remain as visible OCR text. Existing insertion eligibility and context
  transactions still validate selected variables, resources and proof.
- Separate template misses, glyph misses, automatic OCR misses, invalid inputs
  and engine failures. Suggestions are not diagnoses. Fixed-size pixel/gray
  matching reads only the template header to explain an oversized/unreadable
  template before dispatch; scaled/feature matching retains its own rules.
- Keep screenshot toolbar, image algorithms/models, Root connection behavior,
  frame policy, processing history, save conflicts and discard guards unchanged.
- Actual opening of a non-capture workbench exposed an unsupported default
  mapping value (`fit`). Use the supported `letterbox` mapping, consistent with
  project defaults and identity image coordinates. Keep the first crash trace.
- Actual drag injection exposed that a final ACTION_UP coordinate was ignored.
  Apply its final single-pointer delta before committing the selection. Consumed
  cancellation still rolls back and a pinch still discards the single-pointer
  edit. The device regression deliberately puts the final point only in UP.

## Verification

The focused host checks cover workspace isolation, stale geometry, raw draft
retention, fixed template size boundaries and status-specific feedback, plus the
existing OCR configuration and recognition-proof tests. Device QA uses only
synthetic public pixels and a temporary project, the production workbench
controls, actual touch injection and restored Activity orientation. It does not
execute a private project or modify the user's IME choice.

Production, unit-test and Android-test compilation passed. Fourteen focused JVM
checks passed: convenience 6, existing OCR configuration 6, existing preview-proof
2. Their XML is retained in `build/image-workspace-convenience-checks-xml/`.

The initial device opening reproduced the unsupported mapping crash. After its
production-only incremental rebuild, actual landscape filter actions, match
configuration return/real pixel preview and OCR return/orientation checks passed.
An initial save touch selected the identical title; the fixture now selects the
bottom save action. The original Sogou IME did not show a nonzero input window.
The previously authorized test-only keyboard showed a real 352px window; the
test/return actions stayed above it. Filter discard protection, applying and
saving a copy with the original pixels intact passed. The wrapper restored Sogou
and disabled the QA keyboard in its finally block.

Insertion was initially rejected because the synthetic QA project declared only
`core.task`; the fixture now explicitly declares the existing image capabilities.
The first follow-up then stalled before entering any case. A signal-3 dump of the
owned process shows RenderThread waiting in EGL initialization and main waiting
for the hardware renderer, with the test waiting to request Activity rotation.
MuMu 0 was restarted once under the existing permission, keeping data and renderer
configuration. Only the remaining selection/insertion checks were resumed.

Seven distinct workspace cases passed cumulatively across the targeted runs:
landscape filter controls/return, match draft/real preview/stale-result clearing,
OCR draft/orientation return, the visible keyboard/footer, protected filter
apply/save-copy, selection release coordinates, and insertion draft/cancel/single
undo. The final drag uses a release-only endpoint at (110, 60), yielding the
expected inclusive 91 by 41 region from (20, 20). Insertion retains the changed
`qaScratch` selection and `qaOutput`, inserts once and undoes once without saving
the temporary formal project source. The final remaining-only run passed both
selection and insertion cases and reported successful cleanup.

Eighteen additional actual capture/template cases passed on the same final
candidate: six fresh Root captures across portrait, landscape and restored
portrait, each followed by ordinary run and node-test template matching. These
checks validate the saved PNG region and real compiled Rust results. Thus the
completed coverage is 14 host checks and 25 distinct MuMu cases, accumulated from
focused runs rather than one monolithic run. Previously passed cases were not
repeated after unrelated follow-up fixes.

The final installed target APK SHA256 is
`65177663f30e0f935d4fa51951bd26f1fe79c9c6b86215e23c572d9cfc7b506e`;
the test APK SHA256 is
`2ae98806d6e39eaa45fa59f8e7501721b2e3900a49c18a49a61057d02a7c6b1d`.
Final read-only checks confirmed Sogou is the default and only enabled IME, the
QA IME is disabled, accelerometer rotation is 1, user rotation is 0 and the
hardware-keyboard IME setting is restored to 1. The final workspace and capture
QA project paths are absent; interrupted owned QA projects were also removed.

MuMu retains the local Studio 0.1.42/code43 Debug x86_64 candidate. No ARM device
acceptance, new public release, version bump, Git commit, private-script run or
server/Root/configuration/key/project-backup change is claimed. The online
baseline remains the previously published 0.1.42/code43 ARM64 release.

The first Gradle invocation stopped before compilation because `--tests` was
attached to the assembly task; its log is retained. The corrected invocation
places test filters directly after the test task.

- `build/image-workspace-convenience-build-first.log`
- `build/image-workspace-convenience-build.log`
- `build/image-workspace-convenience-device-crash-first.log`
- `build/image-workspace-convenience-compile-default-mapping-escalated.log`
- `build/image-workspace-convenience-device-followup.log`
- `build/image-workspace-convenience-mumu-egl-thread-dump.log`
- `build/image-workspace-convenience-compile-release-pointer.log`
- `build/function-usage-imageWorkspace-20261108T000643.log`
- `build/function-usage-templateCapture-20261108T000730.log`

## Publication Follow-up

The user subsequently requested online packaging on 2026-11-08. This authorizes
Studio 0.1.43/code44, ARM64 only, using the original development signer, full/V4
optimized Release build, compact `packageRelease` and bidirectionally signed
HTTP publisher. Reuse the completed 14 host and 25 distinct MuMu checks; only
necessary package and public-delivery checks are required for this publication.
No device connection/installation, private-script run, new x86 package, Git
commit, backend/site-source deployment or Root/server/key/backup change is part
of this follow-up. Under the standing development retention rule, preview and
archive the replaced public package after publishing so only the latest remains
public. The existing HTTP API cannot delete private archives.

### Published Result

Studio 0.1.43/code44 was published through the original signed HTTP publisher,
ARM64 only. The original development signer, full/V4 model inventory, native
symbol stripping, ZIP alignment, manifest and package-size checks passed. Final
APK size is 36791542 bytes (35.09 MiB), including 30919 bytes of ZIP layout,
signing and alignment overhead. SHA256:
`81f026a46216c3e7b980a0ebd0ccb20c5fc89b379bc0593fe1628b68989cc6dc`.

After the signed preview, replaced 0.1.42 was moved out of public history into
private `archive-20261107T171313Z-fc349da8fd7ac66c`. The two prior archive records
remain identical; the management inventory now contains three private archives.
No private archive deletion is claimed.

The final signed revision is
`b4919dead98f6b5e5e432084bb2b219249566f7d1f78ded2708302c4a77808b3`.
The public no-store manifest has the same raw digest. The complete public APK
download passed size/hash/MIME checks, along with checksum content, Range and the
download page. There is one public ARM64 package, no history/alternative package,
and the replaced APK/checksum return 404. The final signed revision remained
unchanged through verification.

Build and publication evidence is retained in:

- `build/image-convenience-v043-arm64-distribution.log`
- `build/image-convenience-v043-before.json`
- `build/image-convenience-v043-archives-before.json`
- `build/image-convenience-v043-publish-arm64.log`
- `build/image-convenience-v043-archive-preview.log`
- `build/image-convenience-v043-archive.log`
- `build/image-convenience-v043-public-verify.log`
- `build/image-convenience-v043-online-final.json`

No passed application checks were repeated, no new x86 APK was built, and no
device was connected or installed during publication. The earlier MuMu results
remain x86_64 acceptance of the local candidate; package/public-delivery checks
do not establish ARM device UI acceptance. No private script, Git commit,
backend/site-source deployment or Root/server/credential/project-backup change
was performed.
