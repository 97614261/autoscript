# ADR 0223: Compact touch dialogs and configure plugin calls once

## Request

Remove unnecessary elements from touch dialogs and fix selecting the called
plugin twice when adding a visual block.

## Decisions

- Reduce the touch action panel's landscape width from 600 to 480dp and use
  compact 34dp action rows. Keep all seven actions and optional recording.
  Remove the duplicate ready label and long action-description list. Show
  unavailable/loading reasons and permission/reconnect actions only when needed.
- Reduce the touch argument panel's landscape width from 600 to 440dp. Use
  inline compact labels, retain every contract field and coordinate mode, and
  replace the permanent six-duration row with a manual duration plus presets
  dropdown. Only show the coordinate-conversion warning after a mode change.
  Preserve validation, cancellation, unsaved-edit protection and fixed actions.
- The dock used to insert a call, finish its insertion, then set
  `editingCallNodeId`, opening the typed target editor again. Remove that
  post-insertion editor activation.
- Configure named call arguments in the original call panel and carry the
  selected target plus typed arguments together in the existing editor hint.
  Reuse the existing boolean/integer/finite-number/string parser, optional
  omission and legacy hint adapter. Existing target-only hints still work.
- Unconfigured catalog calls enter argument configuration before any mutation;
  selected calls proceed directly. Retain target existence checks and the
  original capability/context/selection transaction, one completion callback,
  one undo, branch identity and save path. Cancelling configuration inserts
  nothing. Editing existing calls remains available.
- Do not change input execution, Root authorization, coordinate mapping,
  runtime contracts, function semantics or project data.

## Validation And Scope

The focused JVM run passed 25 tests: five new call-insertion checks, six
existing jump-hint checks, nine configured-insertion checks, and five touch
capability checks. The call checks exercise a non-first target, all four
scalar argument types, omitted optional values, invalid values, the real
legacy dock adapter, stale-context rejection, both result branches and one
undo. They do not simulate device taps or claim visual acceptance.

Debug production/test compilation, a local x86_64 Debug build, and full Debug
Lint passed (zero errors, 50 warnings and nine hints, unchanged from ADR 0222).
The final library-entry wiring passed an incremental production compile;
the passed tests are not rerun. Evidence: `build/touch-flow-dialog-checks.log`
and `build/touch-flow-dialog-final-compile.log`, with the original profile
test XML and Lint reports.

No version bump, device connection/installation, private script execution,
publication, Git commit, or Root/server/key/backup changes are included.
The public release remains Studio 0.1.46/code47, ARM64 only. The local Debug
build precedes the final one-line library-entry wiring; no delivery APK is
claimed for this request.
