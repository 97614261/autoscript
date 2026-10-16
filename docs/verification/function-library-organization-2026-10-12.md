# Function library organization, 2026-10-12

Decision: [ADR 0087](../adr/0087-unified-visual-function-library.md).
Inventory: [174 visual blocks](../reference/function-library-inventory-2026-10-12.md).

## Scope and removal audit

- Both production EditorDock callers use visual projects; ProjectPage rejects
  historical Lua editing through StudioProjectPolicy. Function documents already
  use visual blocks. No supported caller requires the old Lua catalogue.
- Removed its catalogue builder, five syntax templates, three log example wrappers,
  literal-default generator, utility-default helper and old snippet-replacement
  branch. Removed tests of retired examples, retaining contract whitelist tests.
- Kept all 174 blocks and 159 APIs. The four Legacy blocks remain connected to
  compiler analysis and runtime dispatch, so they are classified separately rather
  than deleted. No project, resource, reference-source or archive file was deleted.
- Recording/image code still uses LuaSnippetGate; restricted reversible plugin
  source editing and runtime Lua contracts remain active.

## Organization

Visual and API catalogues share purpose categories and stable ordering. There are
21 visual categories. Large file, text and image groups use compact subsections.
English API aliases supplement visual search; exact matches rank first. Existing
parameter metadata, capability gating and configuration callbacks are preserved.

Function documents expose a separate read-only generated API reference with no
insertion snippet or button. It is explicitly not reversible plugin source.
Binary/Base64/ZIP file notes no longer incorrectly apply the text 64KiB limit.
Historical parity and development documents now distinguish current status from
their older snapshots.

## Verification

`build.cmd -Verify -MuMuManagerPath D:\soft\MuMuPlayer\nx_main\MuMuManager.exe`
completed successfully. Studio: 582 unit tests, zero failures/errors/skips. Lint:
0 errors, 32 warnings, 17 hints (existing baseline). Debug APK built and installed
on MuMu instance 0 at 127.0.0.1:16384, preserving application data.

Tests cover every block/API exactly once, generated parameter/return/version
metadata, category and alias routing, compatibility preservation, non-insertable
low-level references, file-limit notes, heading behavior and retirement guards.
Repository layout check passed. `git diff --check` passed with CRLF notices only.

This is local code/build verification and installation, not device visual or
private-script acceptance. No Rust contracts or runtime behavior were changed.

## Follow-up: user-requested purpose review

All 174 blocks and 159 API references were reviewed again after the user's
clarification. Confirmation/layout/close-dialog functions now belong to Debug
in the visual catalogue, API documents and dock classification. The right-side
Debug panel provides a Dialogs tab that forwards safe visual insertion hints.
Confirmation/layout functions reuse their existing typed configuration; closing
a dialog remains a zero-parameter block. Pending output/settings are confirmed
before switching to dialog insertion, never silently discarded.

The ProjectPage floating fallback also configures a dialog before inserting it;
confirmation checks project/flow/source/selection, current capabilities and run
state. Cancelling creates no placeholder block. Context changes reset the request.

Crop/transform/PNG saving moved to Image processing, while Unix/monotonic readings
moved to Time. Interface contains only page reads, widget writes and UI commands;
low-level UI.waitEvent remains a read-only Interface reference. Persisted kinds,
capabilities and protocols are unchanged. Updated inventory records all 21 groups.

Repeat `build.cmd -Verify` completed: Studio 585 tests, zero failures/errors;
FunctionLibraryTest 15 tests. Lint remains 0 errors, 32 warnings, 17 hints. Debug
APK was installed on MuMu 0 preserving data; repository layout and whitespace
checks passed. Visual interaction and script execution were not device-tested.
