# ADR 0088: Editor titlebar dragging and display modes

- Date: 2026-10-12
- Status: Accepted

## Decision

Following the user's 2026-10-12 correction, the titlebar puts settings on the
left, the clickable plugin title in the middle, and three controls on the right:
minimize, maximize/restore and close. A separate fullscreen button is removed
because it duplicates enlargement. The bottom-right resize icon uses a matching
diagonal corner grip instead of an unrelated arrow.
Use consistent outlined vector icons with accessible descriptions and active mode
feedback. Title click retains plugin selection; dragging never selects a file.

All non-control titlebar areas initiate drag after touch slop. Gestures beginning
inside a control are excluded and remain control-only. Mode changes preserve the
normal window geometry, editor drafts, selection and runtime state. Maximize fills
the editor viewport without inset. It does not promise to hide Android system
bars or change overlay permissions.
Toggling an active mode restores the normal window. Minimize retains the mode.

Dragging a maximized titlebar first restores the normal window under
the pointer, then moves it. Resizing is only exposed in normal mode. Position
bounds keep both the left settings and right controls visible after dragging or
rotation. The existing native bounded overlay host remains unchanged.

## Verification

Pure geometry tests cover modes, toggle/restore, rotation and tiny viewports,
button-hit exclusion and restored drag positions. Wiring tests retain plugin
selection, minimum/close callbacks and latest gesture callbacks. Android tests,
Lint and Debug packaging are required; installation is not gesture acceptance.
