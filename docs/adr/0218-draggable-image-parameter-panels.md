# ADR 0218: Draggable image parameter panels

## Request

Allow the remaining image parameter panels to move by their titles, so users
can move them away from the current selection or device navigation controls.
No new publication or version bump is requested.

## Implementation

- Reuse one movable host for inline workspace parameters and standalone image
  panels. A small grip identifies the title drag target; back/close buttons,
  editable fields and form scrolling are outside that target.
- Keep the image canvas full-sized behind the floating parameter form. Opening
  or dragging a form does not resize the canvas or alter its image transform.
  The existing borrowed-preview ownership and original-pixel mapping remain.
- Start at the previous side/bottom location, using the same compact form size
  caps. Clamp the full measured panel inside the available control area with
  system safe-drawing/IME insets and a six-dp margin. Fullscreen standalone
  panels fill that area and do not expose a useless drag target.
- Remember normalized travel coordinates per orientation for the current
  workspace. Switching image tools retains placement; reduced keyboard bounds
  resolve the position without overwriting its previous travel fraction.
- Retain the original close/discard, asynchronous cancellation, preview, save,
  overwrite, source-pixel and insertion guards. No change to Root capture or
  runtime algorithms. Placement is session state, not a project mutation.

## Verification

Five focused host geometry checks passed, covering edge clamping, keyboard
shrink/restore, rotation/tiny bounds, former out-of-bounds positions and invalid
geometry. Debug production/test compilation and both local QA APK builds passed
in `build/image-panel-drag-build.log`.

The source-consistent MuMu candidate passed three actual-touch cases: move the
real filter form left without changing canvas bounds, close/reopen at the same
position and use its return/cancel buttons, and drag upward in portrait then
restore the separate landscape position. Header/close/footer bounds remained
inside the canvas. Landscape/portrait screenshots were inspected. Evidence:
`build/function-usage-imagePanel-20261109T154239.log` and
`build/image-panel-drag-device.log`. The fixture cleaned its owned project and
restored orientation; Sogou remains the only enabled/default IME, and rotation
settings remain 1/0.

MuMu retains the data-preserving Studio 0.1.44/code45 Debug x86_64 candidate.
These checks do not claim standalone-panel actual touch, a new physical keyboard
test, manufacturer-specific navigation/cutout acceptance or ARM/Root matching.
The standalone path shares the compiled host and header. No private scripts,
Root settings, server configuration, keys or project backups were touched.

Evidence: `build/image-panel-drag-*` and `build/function-usage-imagePanel-*`.
Online remains Studio 0.1.44/code45 ARM64; this batch does not publish or commit.

## Subsequent Publication Authorization

The user's subsequent request to package and publish authorizes Studio
0.1.45/code46 ARM64, including ADR 0217 and this change. Reuse the 18 distinct
focused host checks and seven actual MuMu cases; retain the original development
signer, full/V4 optimized distribution build, compact `packageRelease` and
bidirectional HMAC HTTP publisher. Only necessary package and public-delivery
checks are required. Do not repeat the App suite or build/install a new x86
package, run private scripts, commit Git or deploy backend/docs source.

Under the standing latest-public-package policy, signed preview precedes
recoverable archiving of the replaced 0.1.44 package. Keep existing private
archives because the HTTP API has no private archive deletion operation.
Do not change Root, server configuration, keys or project backups. Actual
publication results are appended after completion.

## Published Result

Studio 0.1.45/code46 ARM64 was built and published through the original
development-signed full/V4 optimized build and bidirectional HMAC HTTP client.
Incremental Release compilation, essential lint, R8/resource shrinking,
compact `packageRelease`, signature/alignment and native/model inventory checks
passed. The prior 18 host and seven MuMu cases were reused; none was rerun for
publication.

- APK size: 36,789,902 bytes; ZIP layout/signing/alignment overhead: 30,917 bytes.
- SHA-256: `c74883c7d45eb1bf20329c78d3c74b1da2ab1a36ef0defa334b5f41de6df3cb6`.
- Signed preview selected only the replaced 0.1.44 package, recoverably archived
  as `archive-20261109T075601Z-5ce2ea27a6fc3981`. The four existing archive
  records remain identical. The HTTP interface cannot delete private archives.
- Public inventory contains one current ARM64 package, no alternatives and no
  history. Management inventory contains five private archives.
- Public no-store manifest bytes match the signed revision. Complete APK size,
  SHA-256 and MIME, Range/checksum, download-page HTTP 200 and replaced-package
  APK/checksum HTTP 404 all pass. The signed revision remained unchanged at the
  end of verification:
  `f192c2d929d31e0d13d248b5bfd7656cd8bab4fa93473bf9c75d551b8552b90e`.

Evidence: `build/image-toolbar-v045-*` and
`build/verify-release-0.1.45-arm64.ps1`. No new x86 build, device connection or
installation, private-script execution, Git commit, backend/docs-source
deployment, Root/server/key or project-backup change occurred during this
publication. Delivery checks do not add ARM/device UI or keyboard acceptance.
