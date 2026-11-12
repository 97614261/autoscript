# ADR 0226: Interface actions, style copying and consistent previews

## Scope

Implement the user's approved interface-designer items 1-3. No version bump,
Studio delivery APK, installation, device connection or publication is included.
The previous border/default-style work in ADR0225 stays intact.

## Decisions

- New buttons have no default event. Adding one opens its interaction inspector
  with the click-purpose selector first. Reuse the existing run, close, minimize,
  page and plugin event contracts; show plugin names in the choices. Existing
  saved events remain unchanged and other event editors stay available.
- Add a compact Style menu to the existing scrollable canvas action row. Copy
  one control's appearance into a project/editor-local value snapshot; paste to
  one or several selected controls in one undoable commit. Copy colors, border,
  corners, font size/bold/underline and text alignment only. Preserve identities,
  kinds, geometry, contents/options, binding, events, visibility and enabled state.
  The snapshot survives source edits/deletion and page changes but is not written
  to the system clipboard or shared between projects. Unfinished source style
  input blocks copying; paste clears only overwritten numeric style drafts.
- Use shared native drawables for designer/runtime checkbox and radio marks,
  dropdown arrows, foreground borders and button pressed feedback. Clear native
  indicator tinting and reserve dropdown-arrow space independently of background
  styling. Keep hints subdued, show an empty dropdown as Unselected, and mask
  password content. Reuse the same accent, track colors and corner clamping.
- Clip the canvas content against every original rounded ancestor rectangle,
  retaining the existing page/rectangular clip. Keep editing hit targets,
  outlines and resize handles separate. Inherit disabled/hidden preview state;
  dim a disabled runtime subtree once, avoiding compounded alpha in nesting.
  Runtime values, commands, event dispatch and legacy v1 form behavior stay intact.

## Validation

Studio Debug production/test compilation and script-ui Debug/Release production
compilation passed. All 14 focused JVM checks passed: style clipboard/workflow 5,
updated default appearance 5, shared display policy 4. Coverage includes old
button action retention, all 18 control kinds in style-only round trips,
multi-target transfer, source deletion, invalid draft isolation, nested rounded
clip coordinates and inherited state, literal values, hint alpha and empty
dropdown selection. One paste calls one existing undoable commit; UI undo/drag
and native drawable pixels were reviewed in source, not exercised on a device.

Evidence: `build/ui-designer-workflow-checks.log` and the profile test XML.
The command passed once in 5m17s; no broad test/full Lint rerun was performed.
The existing dependency graph rebuilt its internal Runner template, without a
Studio delivery APK. Targeted diff and new-file whitespace checks passed.
No device visual/touch/IME acceptance is implied by model checks and compilation.
Online remains Studio 0.1.47/code48, ARM64 only. No Git commit, private script,
Root/server/key/project-backup changes.
