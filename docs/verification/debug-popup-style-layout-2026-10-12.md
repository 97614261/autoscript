# Compact debug popup style layout, 2026-10-12

Decision: [ADR 0089](../adr/0089-debug-popup-style-and-settings-ownership.md).

The minimize dash is centered at y=10 in its 20-unit viewport. Debug no longer
contains a Run Settings tab, delay picker/state or unused settings panel. Startup
delay remains in the existing Project Settings / Run Settings form and its
validated project save. Style saving preserves all other ProjectDebugSettings.

The shared style editor is extracted into runtime/DebugPopupStyleEditor.kt. A
72dp preview stays outside the scrolling input column. Its brief heading has an
inline help toggle; the long description is hidden by default. Dimension,
position and font/corner fields are paired, colors are full-width with swatches,
and alignment has three direct choices. Each coordinate has a center shortcut.
Duration and restore-default remain available. Short windows and larger fonts
can scroll the inputs without losing the preview or footer.

The numeric/color draft retains incomplete and out-of-range input. Save fails
explicitly rather than committing the last valid value. Preview uses only
validated values and scales large popups uniformly, including font and corners;
small values such as 100x30 remain unchanged and are not enlarged. Actual screen
position is not simulated by the style-only preview. Unsaved close retains the
existing confirmation path. Project/runtime schemas and prompt lifetimes do
not change.

Six tests cover round trips, numeric boundaries, colors/alignment, invalid draft
fallback, preview scaling and UI ownership/wiring. No device visual/gesture QA
or private-script execution is performed.

## Validation

Final `build.cmd -Verify` passed: 607 Studio tests, zero failures/errors. Lint has
0 errors, 30 warnings and 16 hints; no new warnings remain (the old style panel's
two color-parser warnings and delay-state hint were removed). Debug APK built
and updated on MuMu instance 0 at 127.0.0.1:16384, retaining app data. Repository
layout and whitespace checks passed. Installation is not device layout QA.
