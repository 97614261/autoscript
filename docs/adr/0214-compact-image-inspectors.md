# ADR 0214: Redesign image operation inspectors around the screenshot toolbar

## Scope

The user likes the existing screenshot toolbar but requested a redesign of the
subsequent image operation dialogs. Keep the toolbar and implement the new
inspectors locally. The initial design request did not include a version bump,
APK delivery, device installation or online publication; its published baseline
was 0.1.41. The user's subsequent publication request is recorded below.

## Design

- Use a scoped dark slate and blue theme for image inspectors, following the
  screenshot toolbar. Other app screens and variable pickers keep their theme.
- Replace large white cards and tall fields with small section headings, compact
  labeled inputs, actual dropdown choices and checkboxes. Keep RGB color chips
  faithful to their underlying pixel colors.
- Keep the shared canvas prominent: a narrow inspector in landscape and a lower
  inspector in portrait. Small selection/test launchers leave more canvas space.
  Retain configuration orientation and IME avoidance from ADR 0213.
- Keep long parameters scrollable and apply, test, save and insert actions at the
  bottom. Collapse explanatory text by default; show invalid-input errors fully.
- Apply the same treatment to filter, mask, cleanup, coordinate and polygon tools,
  match/OCR tests, glyph authoring, color palettes, image library, recipes, template
  saving and image pipeline insertion. Preserve common/outputs/advanced recognition
  settings and all four matching algorithms.
- Separate submit validity from permission to close. Invalid input must not trap
  the user inside a panel. Existing busy and unsaved-work guards remain in charge.
- Isolate nested option, variable, template and dictionary selectors from the
  parent workspace. The difference-analysis inspector also uses an independent
  context so closing it cannot clear the parent match inspector's close callback
  or borrowed result canvas.

## Preserved Behavior

Pixel processing, models, recognition results, screenshot source selection,
coordinate mapping, frame policy, task cancellation/tickets, resource budgets,
recognition proof, save/overwrite confirmation, insertion position transactions,
undo and unsaved-work protection retain their existing behavior. The main canvas
is reused where it was already shared; no extra screenshot or pixel cache is added.

## Checks

The focused Release run compiled production and test sources and passed 13 checks:
seven workspace layout checks, including the new compact-launcher/keyboard case,
and six existing guarded-navigation checks. The prior ADR 0212 image mapping and
processing evidence is reused rather than rerunning the unchanged suite.

- `build/image-inspector-redesign-checks-first.log`
- `build/image-inspector-redesign-checks-xml/`
- `build/image-inspector-redesign-compile-final.log`
- `build/image-inspector-redesign-compile-analysis-isolation.log`

The first compile log is the successful production-only incremental compile after unifying
the glyph selector and removing a redundant inner OCR dictionary scroll area.
The difference-analysis isolation also passed its production-only incremental compile.
No test suite is repeated for those composition-only follow-ups.

These are host compilation/model checks and source review. They do not establish
actual device rendering, touch, keyboard or ARM runtime acceptance. No screenshot
comparison or device installation is claimed.

During the design implementation, no Studio APK was generated, installed or
published, and no version, Root, server configuration, credential, project backup
or Git commit was changed.

## Publication Follow-up

The user subsequently requested online packaging. Build Studio 0.1.42/code 43
with the existing development signer, ARM64-only full/V4 optimized Release
pipeline, compact final packaging and original bidirectionally signed HTTP
publisher. Reuse the focused checks above. Only package and publication checks
are required; no repeated acceptance suite or device installation is requested.
Keep only the latest public APK under the standing development retention rule;
the HTTP API can archive replaced history but cannot delete private archives.

### Published Result

Studio 0.1.42/code 43 was published through the original HTTP publisher, ARM64
only. The original signer and full/V4 inventory, final ZIP alignment, manifest
identity and package-size checks passed. APK size is 36783310 bytes (35.08 MiB),
including 30917 bytes of ZIP layout/signing/alignment overhead. SHA-256:
`bd94e7d41d97c0d2cab37217c0e30e2e2cbc4ed72c422affb95f12a1be875b28`.

The final signed manifest revision is
`45a3fcf78ed5a7259b27f5f26cdb1ae6192e1af52d1556d2609d320e05cb86f8`.
Its public no-store JSON digest matches that revision. The full public APK
download, size/hash/MIME, Range, checksum and download page passed. Public
inventory contains one ARM64 package and no history or alternative package.

After the signed preview, replaced 0.1.41 was archived privately as
`archive-20261107T041942Z-258e112fd79c841f`. Its public APK and checksum return
404. The previous 0.1.40 archive is unchanged; management inventory now contains
two archives. No private archive deletion is claimed.

Evidence is under `build/image-inspector-v042-*`, including the build, signed
before/after state, publish, archive-preview/archive and public verification logs.
No new x86 build, device installation/connection, private script execution, Git
commit, backend/site-source deployment or Root/server/credential/backup change
was performed. Publication checks are not device UI acceptance.
