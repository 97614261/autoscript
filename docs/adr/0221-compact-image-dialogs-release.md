# ADR 0221: Compact image dialogs and publish the current ARM build

## Request

Reduce unnecessary image-dialog elements, then package and publish using the
existing workflow. Reuse completed checks instead of repeatedly testing.

## Decisions

- Keep the established screenshot toolbar, draggable parameter panels, IME
  avoidance and fixed bottom actions. Do not change the image algorithms.
- Replace full-width preview selectors with a compact original/effect action
  and one status line. Keep actual errors readable in full.
- Put filter color/output choices alongside each other, use dense RGB/tolerance
  inputs, omit the single-rule chip and repeated section headings, and open
  filter preset management only when requested. Keep all rule, preset and help
  operations reachable.
- Pair mask tool/radius controls. Show precise rectangles for rectangle mode
  and argument-only configuration; drawing tools retain undo/redo/clear.
- Pair cleanup mode/foreground and threshold/area or radius. Keep connectivity
  and the decimal-point preset; expose invalid hidden values for correction.
- Reduce threshold/resize explanations to optional help. Save dialogs show
  image dimensions once in the header, use one folder-navigation row and omit
  repeated path/format explanations. Preserve import conflict choices,
  resource protection and cancellation.
- Publish Studio 0.1.46/code47, ARM64 only, including the accumulated ADR0219
  recognition/log changes and ADR0220 image-save repair. This supersedes their
  earlier local-only publication limits.
- Use the original development signer, full/V4 distribution build, compact
  packageRelease and signed HMAC HTTP publisher. Keep only the latest public
  package under the user's standing development policy. The original HTTP
  archive interface cannot delete private archives; retain and report them.

## Verification and Delivery

Reuse ADR0219/0220 focused checks and their successful Debug build/lint.
Only incrementally compile/build the ARM Release and perform the existing
necessary package and public-download checks. Do not install or connect a
device, repeat the full test suite, execute private scripts, commit Git, deploy
the documentation/backend, or modify Root, server configuration, keys or
project backups.

The original ARM64 distribution build and compact packageRelease complete,
including incremental Release source compilation and normal build-time R8 /
vital-lint tasks. No repeated App unit-test suite, full lint or device checks
are run. Required original native/model, signer, identity, alignment and size
checks pass. The final APK is 36,802,062 bytes, SHA-256
`65b28ad06ec47574e57f4b83d0dc3dc5d870c34957bce10bc8bf4df8e355f99b`,
with 30,918 bytes of ZIP/signing/alignment overhead.

The original signed HTTP publisher confirms 0.1.46/code47. After a signed
preview, replace the public 0.1.45 history entry with recoverable private
`archive-20261109T110930Z-8dfea56eb7e4843c`. All five previous archive identities
remain unchanged. The public release has one ARM64 package, no alternatives
and no history; six private archives remain because HTTP has no delete API.

One public verification passes: no-store manifest digest matches the signed
revision; a complete APK download matches size/SHA/MIME; checksum, range,
download-page HTTP 200 and old APK/checksum HTTP 404 agree. The final signed
revision remains unchanged throughout verification:
`1497201728dfbc5d90d70709f75c306d7e7f78c775ef3fa322d4b7d609d1b7cc`.
Evidence: `build/image-dialog-v046-*`. Targeted diff whitespace checks pass.
These build/download checks do not constitute device visual/touch/keyboard or
ARM matching acceptance. No new x86 build or device installation is performed.
