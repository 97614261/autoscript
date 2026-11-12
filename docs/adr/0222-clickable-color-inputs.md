# ADR 0222: Clickable color inputs throughout Studio

## Request

Support choosing colors by clicking throughout project authoring, while retaining
manual entry and the existing screenshot sampling tools.

## Decisions

- Add one compact color button beside color inputs. It opens a palette with
  common colors, a saturation/brightness plane and a hue strip. ARGB fields also
  have an opacity strip and checkerboard preview. Tap and drag are supported.
- Keep edits local to the chooser until confirmation; dismissal does not call
  the parent field callback. Opening an invalid draft does not replace it with a
  default. Invalid text remains editable, and confirmation requires a valid color.
- Preserve the consumer's format: bare RGB hex for filters/glyph palettes,
  prefixed RGB for color properties, decimal RGB for integer APIs and point
  tables, strict ARGB for popup/designer styles, RGB or ARGB for floating windows.
  Keep leading alpha and distinguish numeric-looking hex from decimal values.
- Cover recognition colors, additional colors/glyph palettes, filter rules,
  structured multi-color points, semicolon sample/legacy color lists, integer
  RGB-channel utility inputs, project popup settings, debug popup styling,
  floating-window text/background and individual shapes, UI designer colors and
  UI command color properties. Raw source/JSON editing remains available.
- A palette edit replaces the selected color only; point coordinates,
  tolerances, shape geometry and other rows are preserved. Incomplete manual
  drafts still pass through the original save-time validation.
- Isolate child choosers from the shared image workspace registration. Image
  choosers retain the image inspector theme and draggable viewport; other
  choosers use the compact Studio panel. Keep IME avoidance and initial container
  focus. No tabs, automatic keyboard focus or screenshot/backend changes.
- Bind an open chooser to its owner/field and structured draft identity. Changes
  close stale choosers without recreating the manual input on each keystroke.

## Verification

- Eleven focused JVM cases passed in `build/color-picker-checks.log`: formats,
  invalid/out-of-range values, 2,057 exact HSV/ARGB round trips, channel extremes,
  all thirteen scalar color properties in the catalog, selected palette edits,
  sample geometry/tolerances and floating shape/manual draft preservation.
- Final incremental production compilation passed in
  `build/color-picker-final-compile.log`; the input identity and theme refinement
  reuse the completed model tests.
- Debug build and full Debug lint passed in `build/color-picker-build-lint.log`
  (zero errors, fifty warnings, nine hints). One new warning is the optional
  Modifier parameter order in the color button; it does not change execution.
  Targeted diff whitespace checking passed. Completed tests were not rerun.

## Scope

Local Studio changes only. No version bump, device connection/installation,
private script execution, Root/server/key/backup changes, Git commit or online
publication. Online remains the previously published 0.1.46/code47 ARM package.
Host checks do not establish device visual, touch or keyboard acceptance.
