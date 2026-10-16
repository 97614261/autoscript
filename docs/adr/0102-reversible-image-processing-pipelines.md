# ADR 0102: Explicit processing pipelines in visual blocks

Date: 2026-10-14. Status: accepted implementation increment.

## Decision

Each retained preview owns an immutable operation list rooted at the original.
Undo/redo restores pixels and operations together; bitmap eviction cannot remove
the earlier operations needed to reproduce a surviving result. At most 64 fixed
operations are accepted. No arbitrary filter/code or inferred inverse transform.

Workbench insertion explicitly chooses a runtime screenshot or a declared project
image, plus two distinct declared image variables. Reuse Screen.capture,
Screen.transform, Screen.crop and Screen.release. Add the missing visual
screen.loadimage wrapper for the existing Screen.loadImage contract, including
schema, compiler, function library and native configuration; no new runtime API.
Alternate the two frame slots and release each intermediate source after its
successor is created. Keep the final result in the explicitly selected output
variable for subsequent blocks, with its release responsibility displayed.

Image coordinates remain coordinates of the processed frame. Never generate
touch/automatic-click actions from them. Screenshot dimensions/contents may
change between authoring and execution; fixed pixel crops and recognition ranges
must pass the normal runtime bounds checks, not silently rescale or recapture.

Freeze insertion parameters/context, choose before/after/real child/bottom, stage
exact candidate identities without mutating the live editor, validate the whole
candidate draft set, then recheck formal manifest/source and all editor drafts,
selection, capabilities and runtime state before one undoable commit. Cancel or
failure leaves workbench preview and original editor unchanged. No auto-run,
hidden variable creation, capability rewrite or Lua-as-reversible-source shortcut.

## Remaining gates

Direct recognition-tail configuration, source/orientation device acceptance and
optional provider loading remain distinct tracked gates. Inserting a frame
processing sequence alone does not claim all workbench workflows complete.
