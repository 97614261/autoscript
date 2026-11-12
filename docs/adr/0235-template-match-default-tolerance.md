# ADR 0235: Small default tolerance for new template matches

## Decision

The user requested a default template tolerance of 3 and publication of the
updated ARM64 app. New ordinary, multiple-result, and scaled pixel-template
blocks use tolerance 3 and retain their existing 900-permille similarity.
Fresh saved-image and workbench matching previews use the same tolerance.
The template block presets include 3 alongside the existing exact option 0.

Explicit saved block arguments, existing workspace drafts and saved recipes
retain their own values, including zero. No migration or runtime matcher/API
default is changed. Color search, OCR, grayscale and feature matching retain
their existing settings and semantics. Search rectangles are not enlarged.

## Diagnostic basis

Read-only inspection of the user's designated ARM virtual Android and offline
comparison of their saved images confirmed two separate effects:

- The 41x32 adventure-selection template retained the same PNG bytes and
  decoded runtime fingerprint across the app update. Its original position in
  the saved full-screen image had 307/1312 exactly equal pixels; tolerance 1
  covered every pixel. The existing host pixel-vision matcher found it at
  (160,614) with tolerance 1, while tolerance 0 failed at both 700 and 900
  permille. Historical runs already alternated between found and not found
  before the update, with the same template fingerprint and parameters.
- The 47x30 entry-button template appeared at (1065,469) in the first saved
  image and (1065,514) in the second. The selected map and right-side game
  panel layout differed; the outer dialog and left list stayed aligned. The
  second image agreed with the latest actual runtime hit. Its pixel agreement
  was 623/1000 at tolerance 0, 987/1000 at tolerance 2, and 1000/1000 at
  tolerance 3. A search rectangle ending at Y=534 cannot contain the complete
  second template, which ends at Y=544. The user-selected newer rectangles
  matched the second saved image. A diagnostic union rectangle matched both
  saved images, but was not written to the project or made an automatic rule.

Diagnostics are in ignored build/restart-recognition-20261111 and
build/restart-recognition-20261112. Transfers used base64 and strict decoding
because this device's su/cat path inserted CR characters into binary output.
The initially corrupted transfers were not evidence of corrupt source PNGs.
Device ADB authorization was reconfirmed by the user; the existing ADB service
and host keys were not restarted or replaced. No user script was executed,
project edited, device installed/restarted, or Root/display settings changed.

These observations support a small default for new matches; they do not promise
that tolerance 3 handles animation, resizing, arbitrary lighting, or movement.

## Release scope

Studio 0.1.53/code54, ARM64 only, uses the existing development signer, full
vision distribution profile, compact packageRelease and mutually authenticated
HMAC HTTP publishing workflow. Reuse relevant checks and perform only focused
validation and required package/publication checks. Preserve the user's latest
public package policy through the existing signed archival operation. No Git
commit, new x86 package, backend/docs-source deployment, device installation,
or server/key/project-backup configuration change is part of this release.

## Focused verification

All 27 existing checks passed in one successful targeted Release run:
VisualImageRecognitionDialogTest (15) and ImageRecognitionWorkflowTest (12).
These include the full argument parser, explicit tolerance values including
zero, template-source switches, and preservation of existing parameters.
Production and test compilation passed in the same 5m35s run. No broad suite
or full Lint was requested. The dependency graph rebuilt the internal Runner
template; it is not a separate Studio delivery or device installation.

The initial invocation selected an incomplete workspace Gradle cache and failed
to resolve the offline Android plugin before compilation/tests. The successful
invocation reused the original complete user cache. Both logs are retained:
build/template-tolerance-v053-checks.log and checks-final.log. The catalog-only
default inspection confirmed unchanged color-search/count defaults and the
absence of a grayscale color tolerance. Targeted diff whitespace checks passed.

## Publication result

Studio 0.1.53/code54 ARM64 was built and published through the existing workflow.
Release assembly, compact packageRelease, native/model inventory, signer,
alignment and size gates passed. APK: 36851978 bytes, SHA-256
b6b461be81c3f8eadfbdb16111e10fac90c65ab55f798823b148c543d1d976ff;
ZIP layout/signing/alignment overhead: 30920 bytes (35.14 MiB total).

The signed preview identified only the replaced 0.1.52 package. Under the user's
standing latest-only authorization, it was removed from public history and
retained in private archive archive-20261112T031032Z-1269b0e5ddd63d4f. The
previous twelve archive identities were preserved unchanged. Final public
state has one ARM64 package, no history or alternatives; private archives: 13.

One public verification confirmed the no-store manifest bytes and signed
revision, complete APK size/hash/MIME, Range, checksum file, download page
HTTP 200, and replaced APK/checksum HTTP 404. The final signed revision remained
e2f759323fc80d5cba68d647e03f86748cb91946b8a85c3a205203f83d94e8da.
Evidence: build/template-tolerance-v053-*. No extra App tests or full Lint
were repeated. No device installation/restart, private-script execution, Git
commit, x86 build, backend/docs-source deployment, or server/key/project-backup
configuration change occurred. Host checks and publication do not constitute
new device UI or game-script execution acceptance.
