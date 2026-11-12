# ADR 0216: Single-page image recognition and polygon template origin

## Scope

The user requested removal of the image form's top tabs and redundant elements,
with a compact workflow inspired by the old form. They also reported polygon
templates matching only after the original search range was enlarged. The
published baseline remains Studio 0.1.43/code44 ARM64; this request does not
include a version bump, publication or Git commit.

## Form

- Remove the common/output/advanced tab bar, repeated capture summary, section
  headings, default environment notice and default branch explanation.
- Keep one compact source/capture/type row, a small selectable template preview,
  and direct template-range, screen-selection and whole-screen shortcuts.
- Use two bounded scroll columns in sufficiently wide landscape layouts, and a
  single column in portrait, narrow layouts or larger font settings. The result
  reader uses one column because it has no template/range column.
- Keep common matching controls visible. Expand result variables and advanced
  options inline; all existing argument editors, variable selectors, kind
  changes, wait/retry, save/discard guards and insertion validation remain.
- Put found/X/Y ahead of optional template/score/image outputs. Actual device
  opening exposed the old order hiding these common bindings below the viewport.
  Shorten long matching labels while keeping their units.
- Fix cancel/confirm at the bottom. With a short IME viewport, the navigation
  joins the scrollable form. Environment checks and explanations remain
  available under advanced help.
- "Template range" clears search polygons and dynamic boundary overrides.
  A newly captured template also clears these overrides before applying its
  original-screen rectangle. A template mask and a search-area polygon are
  different concepts: requiring the template's entire bounding rectangle to
  fit inside its triangular mask can reject the original location.
- Prefer the recorded frame dimensions for the whole-screen shortcut. Without
  those dimensions, label the project-design fallback honestly. Old templates
  without a position hint give an explicit rescreenshot/manual-selection hint.

## Polygon Save

Previously, any processing operation discarded screenshot provenance. A
polygon mask followed by a crop preserves both original pixels and scale, so
discarding its screen offset was unnecessary and could select a stale design
range. Direct polygon saving also saved the full rectangle rather than masking
its corners.

- Preserve PNG original-screen metadata through mask/crop-only pipelines.
  Reverse boundary mapping adds all crop offsets once. Resize, orientation and
  color changes continue to omit this provenance.
- Direct polygon saves preserve interior source pixels and make the exterior
  transparent. Freeze only the selected rectangle, translate polygon vertices
  into that patch, and reuse the existing mask processor and PNG import path.
- Keep original-screen coordinates separate from patch-local encoding bounds.
  Preserve the existing five-second cancellation budget, overwrite/import
  transaction, snapshot guard, temporary bitmap cleanup and capture callback.
- Saving a polygon alone never marks the entire rectangular preview as saved,
  including a polygon whose bounding box equals the full image.

## Verification

- 26 focused JVM checks passed: compact layout/polygon processing (5), PNG
  provenance (9), and recognition workflow regression (12).
- Debug production/test source and x86_64 QA APK compilation passed. A final
  incremental candidate includes the selected-patch save optimization; the
  already-passed JVM batch was not repeated.
- The initial Gradle invocation incorrectly split an unquoted PowerShell
  property. Its log is retained; the quoted invocation succeeded.
- The first device attempt did not activate the save footer with the duplicated
  "save image" label. Its screenshot/log and successful cleanup are retained.
  The test now selects the observed footer bounds explicitly, as existing save
  acceptance fixtures do. This required only a test APK rebuild.
- Direct polygon save, alpha/origin checks and real Rust matching at the exact
  original ROI passed. A later toolbar-scroll attempt navigated to the test
  panel instead of reaching crop; preserve that failed fixture attempt. The
  processed-crop case instead exercises the shared bitmap processor, provenance
  helper and actual PNG import directly, then real matching. It passed; this is
  not claimed as touch acceptance of the crop toolbar action.
- Opening result bindings exposed their priority problem. Preserve that failed
  UI assertion and screenshot; only the remaining form checks are repeated after
  correcting the order. Successful polygon checks are reused.

- Four distinct MuMu Android 12 x86_64 checks passed across the focused runs:
  direct polygon save/alpha/origin/exact-ROI matching; processed polygon PNG
  import/origin/exact-ROI matching; the landscape form with visible shortcuts,
  no tabs, output/advanced disclosures and real confirmation; and the portrait
  form with fixed footer and template-range selection.
- The final form run passed both remaining cases, with cleanup confirmed:
  `build/function-usage-imageCompact-20261109T121842.log`. Final synthetic UI
  screenshots are `build/image-compact-form-landscape.png` and
  `build/image-compact-form-portrait.png`; both were visually inspected.
- Reuse the two successful polygon cases after the final change to labels and
  output ordering. They use the same production save/processing implementation;
  do not claim they were repeated on the last form candidate.
- MuMu retains the local 0.1.43/code44 Debug x86_64 candidate. No physical/remote
  ARM device, real game, live Root capture, keyboard occlusion or crop-toolbar
  touch acceptance is claimed. Owned QA projects are removed; no private scripts
  were run, and no IME/Root/server/key/backup configuration was changed.

Evidence: `build/image-compact-form-*` and
`build/function-usage-imageCompact-*.log`. No publication or Git commit occurred.

## Other Image Forms And Release Authorization

The user subsequently requested a sweep of the other image dialogs and online
publication. This authorizes Studio 0.1.44/code45 ARM64 using the existing
development-signed full/V4 distribution and bidirectional HMAC HTTP process.
It supersedes the earlier no-publication scope for this batch. Reuse the 26
host checks and four distinct MuMu checks above; only incremental Release
compilation, package checks and public delivery verification are required.

- Find-image and OCR tests put environment status/recheck and static guidance
  inside the existing collapsed help row. Results, invalid inputs and the V5
  candidate warning remain visible. Preserve model/algorithm selection and
  every actual configuration field.
- Extension forms show the capture source selector directly, with its static
  description in help. Keep scratch-variable overwrite/release confirmation.
- Save-image forms omit the empty folder-list box and duplicate destination
  row. Existing location navigation, folder creation and loading/errors remain;
  the full destination remains available in help alongside save behavior.
- Pixel adjustment folds the static resampling explanation. Pipeline insertion
  removes a heading around its single source selector, preserving variables,
  overwrite/release warnings, proof guards and insertion transaction.
- Reviewed filter, cleanup, mask, polygon, image library, glyph and frame forms;
  retain their functional tool/model/category selectors. The image home
  categories still organize real tools and are not redundant form tabs.

The additional sweep is a source review and compilation change; it does not
claim new device, keyboard or ARM visual/touch acceptance. No new device
installation, private-script run, Git commit, backend/docs-source deployment,
Root/server/key or project-backup change is included.

## Published Result

- Studio 0.1.44/code45 ARM64 was built and published using the original
  development signature, full/V4 distribution profile and HMAC HTTP scripts.
  Incremental Release compilation, essential lint, minification, compact
  `packageRelease`, native/model inventory, signature and alignment checks pass.
- APK: 36,791,610 bytes; SHA-256
  `b4ae6947127c3052c493b9e2eec9e138343eb03782574791a82e19d82c36667f`.
  ZIP layout/signing/alignment overhead is 30,919 bytes.
- Signed preview identified only the replaced 0.1.43 package. It is removed
  from public history and retained as
  `archive-20261109T060609Z-3032bc7e25bccf08`. The three previous private
  archive records remain identical; the HTTP interface cannot delete them.
- Public delivery checks pass: no-store manifest bytes match the signed
  revision; complete APK size/hash/MIME, checksum and Range agree; download
  page returns 200; replaced APK and checksum return 404. Public inventory
  contains one current ARM64 package and no history/alternatives. Private
  management inventory contains four archives.
- Final signed revision:
  `68c18604ff05e2bef80635e3704d38b46389792ba18027dae2a5f8832710f5de`.
  It remained unchanged at the end of public verification. Evidence:
  `build/image-compact-v044-*`.

No already-passed App test was repeated, new x86 package built or device
connected/installed for this publication. Publication verification is not
additional device visual/touch/keyboard or real-game ARM acceptance.
