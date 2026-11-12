# ADR 0227: Designer properties, bindings, display notices and combinations

## Scope

Implement the user's approved next items 1-4 in the interface designer. Reuse
the existing interface schema, runtime event/binding contracts, compact UI and
undo/save/draft protections. No packaging or publication is requested.

## Decisions

- Show the events the native control can emit: content/state/value changes for
  inputs and toggles, selection for choices, click/long-click where supported,
  and a meaningful drag-end label for sliders. Existing unsupported event
  records are preserved and can be explicitly cleared. Filter plugin targets
  against the existing supplied controlId/event/value parameter contract;
  retain a warning and reselection path for an old unavailable target.
- Hide container content and ineffective required switches. Keep a legacy
  required flag explicitly removable. Progress minimum is fixed to zero, but
  an invalid old minimum remains editable for repair. Continue hiding text
  styles on nontext controls; input labels describe their placeholder purpose.
- Reuse the searchable compact variable picker with name/type/scope metadata
  and plugin names. Filter type-incompatible and already-bound targets while
  retaining the current eligible binding. Match ProjectStore: boolean controls
  bind numeric variables as 0/1, but boolean entry parameters stay boolean.
  Show the current binding in every inspector tab, provide explicit detach,
  and preserve an unavailable old binding until the user changes it.
- Add nonblocking display notices for clipping against the page/any ancestor,
  long single-line content/options and insufficient text height. Scrollable
  choice content is informational and does not receive an error badge.
  Existing schema validation remains authoritative. Debounce measurement by
  250 ms, run text layout off the UI thread, cancel superseded work and show
  results only for the matching immutable draft. Toolbar menu entries and
  small control badges locate the relevant layout inspector.
- Add text/input, label/switch and confirm/cancel combinations through the
  existing toolbox. Share the original default-control factory. Reserve one
  complete nonoverlapping root-page slot before creating either member; respect
  the control budget and requested page, assign unique IDs, select both controls
  and commit once for one undo. Failure leaves the model unchanged. Confirm/
  cancel explicitly chooses run/close actions; ordinary new buttons remain inert.

## Validation

Studio Debug production/test compilation and all 15 focused JVM checks passed
in one 2m39s command: properties/bindings 5, combinations 4, notices 5, and the
affected all-18-control default-style round-trip regression 1. Coverage includes
scope identity, duplicate binding exclusion/release, numeric boolean variables
versus boolean parameters, plugin parameter compatibility, old event retention,
atomic combination rejection/capacity/page/ID handling, ordinary inert buttons,
ancestor/page clipping, long values/hints/options, informational scrolling and
invalid option drafts. Tests inject text measurements; actual Android text
measurement, pointer gestures and native popup appearance were not device tested.

Evidence: `build/ui-designer-convenience-checks.log` and profile test XML.
Targeted diff and new-file whitespace checks passed. No full Lint/test rerun,
Studio delivery APK, version bump, device connection/installation, publication,
Git commit, private script or Root/server/key/project-backup changes are included.
The existing internal Runner template dependency was up to date. Online remains
Studio 0.1.47/code48, ARM64 only.

## Subsequent authorized publication

The user subsequently requested packaging and online publication. Studio
0.1.48/code49 now includes ADR0225, ADR0226 and ADR0227, ARM64 only. Reused the
existing focused checks; the original development-signed full/V4 distribution
build, compact packageRelease and required package checks passed. No broad
test/Lint rerun or device installation was performed.

- APK: 36,835,274 bytes; layout/signing/alignment overhead: 30,919 bytes.
- SHA256: `ec1a1bfa5c462197f013d64da58e7ee5d92b4bdff67e95b21ba2468c31d2822a`.
- Published through the existing bidirectional HMAC HTTP workflow. After signed
  preview, replaced 0.1.47 moved to private recoverable archive
  `archive-20261110T085519Z-eccb986b6b178492`. All seven prior archives are unchanged.
- Public inventory contains one current ARM64 package, no history/alternatives;
  private management inventory contains eight archives.
- Public no-store manifest digest, signed revision, full APK digest/size/MIME,
  Range, checksum and download page passed once. Old APK/checksum return 404.
  Final signed revision remained
  `8057b999b4a69dcf8f2aad3996ef27081d80a4765ab0af6286122fa77d459c74`.

Evidence: `build/ui-designer-v048-*`. No Git commit, private script, Root/server
configuration/key/project-backup change, backend or documentation-source
deployment. Publication checks do not establish device visual/touch/IME or
actual plugin execution acceptance.
