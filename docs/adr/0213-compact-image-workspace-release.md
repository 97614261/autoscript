# ADR 0213: Refine the shared image workspace and publish 0.1.41

## Scope

The user requested one more optimization round and online publication. This
extends ADR 0212 and supersedes its local-only delivery limit for this release.
Use the existing ARM64-only, development-signed, full/V4 optimized Release build
and bidirectionally signed HTTP publisher. Include the accumulated ADR 0210/0211
two-category logging changes. No Git commit or device installation is requested.

## Changes

- Keep Clear beside the mode icon outside the scrolling toolbar. Hide the
  undo/redo pair when neither action is available.
- Group processing actions by purpose in the existing compact grid. Keep save
  whole image, save selection and insert blocks in a separate bottom action area.
  Label polygon bounding-box saves explicitly rather than implying a masked save.
- Use one compact title in inline confirmation panels and compact numeric inputs
  for threshold and image dimensions. Preserve standalone dialog entry points.
- Apply IME avoidance to the whole workspace, without another inset on its
  parameter panel. Use configuration orientation so an already resized portrait
  window cannot switch to a landscape sidebar when the keyboard opens.
- Bound side panels to 48 percent of the available width and 340 dp. Bound lower
  panels to 360 dp, expand their share while typing, and retain canvas space when
  height permits. Long parameter content and bottom actions remain scrollable.
- Retain the shared canvas, draft-discard protection, save/template backfill,
  recognition proof, insertion transaction and original pixel-coordinate mapping.
  Do not change Root behavior, runtime frame caching or recognition results.

## Necessary Checks

Reuse the 44 passing ADR 0212 checks and the existing ADR 0210/0211 evidence.
Six new layout checks passed, followed by six passing checks after adding the
configuration-orientation boundary case. Both production and unit-test Release
sources compiled. The first result is retained rather than overwritten:

- `build/image-workspace-v041-checks.log`
- `build/image-workspace-v041-checks-first.xml`
- `build/image-workspace-v041-checks-final.log`

The existing distribution script built ARM64, reran final packaging for a compact
ZIP, and passed native/model inventory, original signer, manifest, alignment and
size gates. There was no repeated full acceptance suite. Evidence is
`build/image-workspace-v041-arm64-distribution.log` and the profile size report.
These checks do not establish actual device rendering, keyboard or touch behavior.

## Published Result

- Studio 0.1.41, versionCode 42, ARM64 only.
- APK bytes: 36763770; ZIP layout/signing/alignment overhead: 30920 bytes.
- SHA-256: `1ed64162c32b9f0a55b9629044b8d510b7adb7471552d767a3606fd69dbd935f`.
- Existing signer SHA-256:
  `8e48ea72d827f96ea57c6b7bf4d566bac7581fa23106324bf3296fa9e2024f6c`.
- Public APK: `/downloads/zhigou-0.1.41-dev-1ed64162c32b.apk`.
- Final signed manifest revision:
  `5ddaa244a8d7dc8cc76ab00baa69b767b0f0ddb6199519cb24bce71920866e13`.

The original HTTP publishing script confirmed the uploaded identity. After a
signed preview, the replaced 0.1.40 package was removed from public history under
the user's standing development-retention authorization. Its recoverable private
archive is `archive-20261106T102446Z-5c74205060bbab91`. Public inventory contains
one current package and no history; management inventory contains this one archive.
The server's HTTP interface does not delete private archives, and none is claimed
deleted here.

The public no-store JSON digest matches the signed revision. Full APK size/hash
and MIME, Range, checksum file and download page passed; the old public APK and
checksum return 404. Evidence:

- `build/image-workspace-v041-before.json`
- `build/image-workspace-v041-publish-arm64.log`
- `build/image-workspace-v041-archive-preview.log`
- `build/image-workspace-v041-archive.log`
- `build/image-workspace-v041-public-verify.log`
- `build/image-workspace-v041-online-final.json`

No new x86 package, device connection/installation, private-script execution,
server configuration/credential/project-backup change, backend or documentation
deployment, or Git commit was performed.
