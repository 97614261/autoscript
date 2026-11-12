# ADR 0225: Interface borders and default appearance

## Request And Findings

Review interface-designer border support and improve the default appearance.
The existing schema, saved model and runtime commands already carry border
color/width and corner radius. Zero-width transparent defaults made the two
independent inputs difficult to use. Native borders were painted in the
background, underneath images/child content and subject to widget tinting.

## Decisions

- Put explicit border visibility, none/thin/standard/bold presets, color and
  numeric width/corner controls near the top of the style inspector. Setting
  a visible color enables a zero-width border; enabling width supplies a
  nontransparent color if necessary. Runtime command semantics stay explicit.
- Share visible-border alpha/width handling with the canvas and runtime.
  Native borders draw in the foreground, clear native background tinting,
  clear the foreground when disabled, and clip contents to the saved corners.
  Buttons use a flat surface; root text insets account for visible borders.
  Remove the canvas-only container frame that appeared even with no border.
- New designer pages use a soft gray canvas. New controls use dark text,
  white outlined inputs/lists, transparent labels/toggles, blue primary
  buttons, a pale navigation surface and white outlined containers.
  Keep compact geometry and the original serialized property format.
- Default/primary/soft/outline/transparent presets apply explicitly to the
  selected controls. Preserve positions, dimensions, font sizes, content,
  bindings, visibility, events and existing pages. Loading a saved interface
  does not silently apply the new appearance.
- Hide text-only style inputs for controls that do not render text. Width
  presets replace only stale width input; appearance presets replace only
  stale border/corner inputs. Other unfinished values still block saving.

## Validation And Scope

Studio Debug production/test compilation and script-ui Debug/Release
production compilation passed. All 34 focused JVM checks passed: appearance
5, designer model 16, saved-design round trips 2, property drafts 4, frame
state 2 and runtime properties 5. The new frame test covers 16 alpha/width
combinations and explicit disable/restore commands. All 18 newly styled
control kinds serialize and reload with the original compact geometry.
The original dependency graph rebuilt its internal Runner template; no
Studio delivery APK was requested or produced. No full Lint/test rerun was
performed. Targeted diff and new-file whitespace checks passed.

Evidence: `build/ui-designer-appearance-checks.log` and the existing profile
test XML. The model tests do not claim native foreground pixels, device
touch or keyboard acceptance.
No Studio delivery APK/version bump, device connection/installation,
publication, Git commit, private script, Root/server/key/project-backup
changes are included. Online remains Studio 0.1.47/code48, ARM64 only.
