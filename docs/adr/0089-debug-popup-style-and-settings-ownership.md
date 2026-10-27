# ADR 0089: Debug popup style and execution settings ownership

- Date: 2026-10-12
- Status: Accepted

## Decision

The user's latest instruction moves execution settings out of Debug. Startup
delay already exists in Project Settings / Run Settings and remains there; the
duplicate Debug tab, delay picker, state and unused panel are removed. Debug
continues to insert output/dialog blocks and edit the shared popup style. Saving
style preserves startup delay, prompt filters, logging flags and other settings.

Style editing uses a fixed 72dp preview under a compact heading, followed by a
separately scrollable input area. Width/height, X/Y and font/corner use paired
inline inputs; colors use full-width inputs with swatches, alignment uses three
direct choices. Long explanations are only shown after a help toggle. Large
text, errors and short viewports retain bounded scrolling. The preview uniformly
scales down dimensions, font and corner radius without changing saved pixels or
pretending to show actual screen coordinates. It never scales small popups up.

Raw numeric/color text belongs to one editor draft. Invalid or incomplete input
stays visible, uses safe valid values for preview and blocks style save. Closing
an unsaved style draft requires discard confirmation. Saving commits only a fully
validated PopupStyle through the existing checked project-settings callback.
No project format, capabilities or runtime prompt lifetime changes are made.

The titlebar minimize vector uses y=10 in a 20-unit viewport so its dash is
centered in the same icon box as maximize and close, rather than drawn at y=15.

## Verification

Tests cover style round trips, all numeric bounds, incomplete colors/text,
safe preview fallback, uniform preview scaling and UI/settings wiring. Android
unit tests, Lint, Debug packaging and data-preserving MuMu installation are
required; installation is not device layout acceptance.

## 2026-10-25: Explicit save acknowledgement

The user reported a successful style save followed by an unsaved-content warning
on a phone, and no visible success feedback in the configuration window. The
form previously used raw text versus the incoming canonical style as its dirty
check and relied on the background editor's save notice. Equivalent numeric or
color spelling can remain dirty without a settings-key change; pending output
content was also described together with unsaved settings.

Return the actual persisted debug settings through a one-shot completion after
the existing checked disk commit. Only the matching live form request advances
its saved baseline and displays the compact in-form save message. Normalize the
acknowledged draft only if it has not changed since submission; retain later
edits as unsaved. Failure, cancellation or a mismatching result does not report
success and preserves input for retry. No duplicate submission or close while
saving; closed-form and old-request results cannot affect a new form. Keep the
window open for continued adjustment. Discard confirmation distinguishes an
unsaved style from output that has not been inserted as a block.

No project format, runtime prompt lifetime, server publication or device action
is changed by this fix. Targeted host checks and device verification are separate.

Targeted PopupStyleDraftTest and RuntimeLogSettingsDraftTest passed 18 tests;
production and Android-test Kotlin compilation passed. The first compile exposed
an unconstrained generic result inferred as Unit; the disk-commit call now states
ProjectSnapshot explicitly, and the same focused command passed. No new APK was
packaged, installed or published, and no phone behavior was claimed as tested.

Subsequent explicit publication on 2026-10-25 included this fix in Studio
0.1.7 / 8, with the update-prompt adjustment under ADR0137. Both optimized
architecture packages and actual public downloads were verified. This later
publication does not add a phone-interaction acceptance result.

## 2026-10-25: Empty output choices are not pending content

The user's 0.1.7 screenshot showed the saved-style success message and the
output-only discard confirmation together. The confirmation was not a failed
style save: the output dirty check also treated channel, expression toggle or
log-level changes on an empty new output as pending content. Save acknowledgement
alone therefore did not cover the whole close path.

Freeze the typed initial output snapshot with its owning form. For a new output,
channel/format choices alone do not require discard confirmation; actual text
(including incomplete input) or a selected variable still does. Existing-block
edits remain protected, including clearing their content or changing a parameter.
Use the same predicate for closing and choosing a dialog function. Do not clear
real output drafts merely because the separate popup style was saved. When only
output is pending, name that content in the confirmation title and let the user
return directly to the output page to inspect it. Saved style and pending output
remain separate operations; unchanged existing output closes without a warning.

This follow-up is initially code-only: no version bump, device installation or
online publication. The currently published package remains 0.1.7 / 8 until a
separate delivery is performed. Add focused regressions for empty choices,
real/partial output, existing edits and saved-style-plus-empty-channel closing.

Focused DebugOutputCodeTest (9) and PopupStyleDraftTest (16) passed with zero
failures, errors or skips; production and host-test Kotlin compiled. The combined
regression explicitly checks saved style plus an empty selected popup channel
allows closing, while real pending output remains protected. No device action,
new APK or online change was performed for this follow-up.

The user subsequently requested replacing the online package. Prepare Studio
0.1.8 / 9 with this exact follow-up, preserving the development signer, full/V4
and updater checks. Reuse the 25 focused test results without claiming a new
device acceptance. The signed online baseline is 0.1.7 / 8 with eight packages;
the oldest 0.1.4 pair needs separate explicit archive approval and user execution
before publication. Local packaging is not online replacement.

Both 0.1.8 / 9 optimized candidates have now been built with the existing
development signer and full/V4 configuration. ARM64 is 36,398,906 bytes,
SHA-256 `5862e28bb28ffdcfd883a14b2ba28935a20d24bb1ec5a93d817883ead41d766f`;
x86_64 is 51,652,466 bytes,
SHA-256 `38e025281717b3c0e2d4f5b12542641bb986acfab45fa800162b5620d36a8a89`.
The existing distribution wrapper checked package identity, signature, ABI,
model digests, compact layout and alignment; the 25 focused regressions were not
repeated. No device installation or online upload has occurred. The prepared
one-off 0.1.4 archive script is local only, syntax checked but not executed;
explicit approval and user server execution remain required before publishing.

Publication subsequently completed on 2026-10-25. The user upgraded the signed
publisher, then explicitly approved archiving only the 0.1.4 pair via ADR0158's
new live API and publishing 0.1.8. The archive succeeded with exact signed
readback; the original six retained package identities and both frozen candidate
hashes were verified before upload. Both 0.1.8 / 9 optimized candidates above
were published and the complete actual public downloads matched their exact
sizes and SHA-256 digests. The public no-store manifest's raw digest matched
signed revision `4e8033878b33043c025b9cd50343a8f89122faf1093ad9d8902979568b5f198c`;
the download page returned HTTP 200 and the revision stayed stable through
verification. No device installation or new interaction/performance acceptance
was performed. This delivered follow-up supersedes the earlier local-only
status without changing the output protection or saved-style semantics.
