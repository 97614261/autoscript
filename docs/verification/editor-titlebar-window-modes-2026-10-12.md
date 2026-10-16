# Editor titlebar and display modes, 2026-10-12

Decision: [ADR 0088](../adr/0088-editor-titlebar-window-modes.md).

This records the initial five-control implementation. The user's subsequent
correction places settings on the left and merges enlargement into one button;
see [the follow-up record](editor-header-controls-correction-2026-10-12.md).

The main visual editor titlebar uses a 36dp height, a clickable plugin title and
five 28dp control columns: fullscreen, minimize, maximize/restore, settings and
save/close. All controls use consistent 18dp outlined vector icons. Active modes
have a blue background; close has a danger tint. The now-unreferenced old
`editor_minimize_24.xml` is removed, with its replacement in
`editor_window_minimize_20.xml`; shared settings and close resources remain.

The whole non-button titlebar participates in dragging after touch slop. A
gesture beginning in a button column cannot drag the window. Title taps retain
plugin selection, while a consumed drag cancels the title click. Latest callbacks
are read without restarting a gesture during normal position updates.

Maximize fills the editor viewport with a 6dp margin; fullscreen has no margin.
Neither changes Android system bars or overlay permissions. Both preserve normal
window geometry, drafts and selection; toggling the active mode restores normal
size. Minimize retains the mode. Dragging an enlarged window restores normal
size under the pointer before moving. Resizing is available only in normal mode.
Drag bounds reserve all five controls and a small non-button grab area.

Five new unit tests cover mode/restore, rotation and tiny viewports, control hit
exclusion, restored drag positions and source wiring. Save/close, minimize,
runtime handling and the native bounded overlay host retain their existing paths.

## Validation

Final `build.cmd -Verify` passed after removing the obsolete minimize resource:
601 Studio tests, zero failures/errors; Lint has 0 errors, 32 warnings and 17 hints
(the existing baseline). Debug APK built and installed on MuMu instance 0 at
127.0.0.1:16384 with data retained. Repository layout and whitespace checks passed.

Actual device gesture/visual acceptance and private-script execution were not
performed. Pure tests and installation are not evidence of device gesture QA.
