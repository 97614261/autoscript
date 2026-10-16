# Editor header controls correction, 2026-10-12

Decision: [ADR 0088](../adr/0088-editor-titlebar-window-modes.md), updated for the
user's latest correction. Settings is now left of the clickable plugin title;
the right side contains only minimize, maximize/restore and save/close. Separate
fullscreen state, callback and both associated vector assets are removed.
Maximize fills the editor viewport and toggles back to the saved small window.

Header hit testing excludes the left settings column and all three right control
columns. The title and non-button gaps remain draggable after touch slop, with
title taps selecting a plugin. Horizontal movement keeps both control groups on
screen rather than permitting the left settings button to become unreachable.

The bottom-right resize handle now uses a three-stroke diagonal corner grip with
the same 1.7-unit rounded outline as header icons, a neutral tint and a semantic
description. The obsolete arrow resource is removed. Resizing remains available
only in the normal window and uses the existing resize callback.

Five unit tests cover single enlargement state, restoration, viewport adaptation,
left/right button exclusion, restored movement, control order and grip wiring.
Device gesture/visual acceptance and private scripts are not exercised.

## Validation

`build.cmd -Verify` passed: 601 Studio tests, zero failures/errors. Lint has
0 errors, 32 warnings and 17 hints (existing baseline). Debug APK built and
updated on MuMu instance 0 at 127.0.0.1:16384 with application data retained.
Repository layout and whitespace checks passed. Installation is not gesture QA.
