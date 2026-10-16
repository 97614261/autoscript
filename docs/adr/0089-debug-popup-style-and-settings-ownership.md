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
