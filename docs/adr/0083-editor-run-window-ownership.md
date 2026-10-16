# ADR 0083: Editor testing retains the editor window

Date: 2026-10-12
Status: accepted by the user's latest decision

## Decision

Editor Run and idle Single Step are editor-owned tests, not project Launch.
Saving, compiling and submitting these tests never hide or minimize the editor.
The first footer button becomes Stop; Pause/Continue remains a separate control.
Minimize alone swaps the editor for its editor ball. Clicking that ball always
restores the editor, including during a test. A terminal callback does not undo
an explicit minimization. Picker/capture/recording suppression remains unchanged.

Project Launch retains the configuration-first flow and formal runtime ball.
Studio sets the control-ball policy inside session-bound project preparation:
off for editor tests, on for formal launches. Runner omits this override and
retains its user-selected policy. Closing an editor during an active test hands
control back to the runtime ball; the runtime notification remains available.

Idle Single Step executes the isolated selected subtree from ADR 0082 without
the project's startup delay. Paused Single Step advances the existing VM.
An accepted request is not completion: editor feedback distinguishes preparation,
submission, observed execution, completion, stopping and failure. It must not
leave a save/compile success message as the apparent result of executing a step.

## Scope

This supersedes the editor-run hiding portions of ADR 0044/0045 and subsequent
constraints, not the formal configuration-window behavior or runtime safety.
No original plugin is rewritten or private script executed for validation.
