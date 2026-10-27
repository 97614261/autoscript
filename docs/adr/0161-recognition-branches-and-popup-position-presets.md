# ADR0161: Common image-search branches and popup position presets

Date: 2026-10-25

## Decision

Pixel template search v3 and grayscale search v2 support the existing success/failure
child-block structure. A match runs success; a completed miss runs failure. Errors,
cancellation and frequency-skipped visits do not execute either branch. Existing
runtime APIs, algorithms, output variables and capability checks remain unchanged.
The compiler accepts legacy leaf versions and new versions without child blocks;
when present, both success and failure blocks are required and other slots are rejected.

Catalog migrations only advance versions and preserve identities and arguments.
New blocks contain both empty branches. Explicitly saving parameters of a migrated
leaf creates the two empty blocks through the project-scoped identity allocator in
the same undoable edit; loading alone does not invent identities. Existing branch
children survive parameter changes, source editing, copying and undo. Shared view
headings remain selectable insertion targets, not executable nodes.
Restricted source round-trips preserve migrated leaves without inventing branches;
an existing branched node cannot silently lose its children through leaf syntax.

Popup style editing adds the eight compass positions and the existing center in a
compact 3x3 grid. Shortcuts use current physical screen pixels and the validated
popup dimensions, matching runtime clamping; centered axes keep the -1 sentinel.
The debug style editor and project run settings share this component, using the
current unsaved dimensions and respecting each form's enabled state.
They update only the draft X/Y values, never save or display a script popup.
Manual coordinates, save acknowledgements and dirty-close confirmation remain.
Stored coordinates are not orientation anchors; changing orientation does not
silently rewrite saved project configuration. Invalid dimensions disable shortcuts.

## Verification Boundary

Use focused compiler execution and editor/style host tests, then compile Kotlin.
No automatic package, device installation or online publication is authorized by
this change request. Host checks do not establish actual device visual acceptance.

## Results

- Schema check passed: 652 JSON documents, 230 schemas, 201 API functions and 218 block definitions.
- All 138 flow-compiler tests passed, including real Lua execution of found/missing,
  numeric/boolean outputs, frequency skips and abort propagation.
- Production Kotlin and test Kotlin compiled; all 49 focused Android host tests
  passed for editor branches, legacy/source/copy/undo compatibility and popup drafts.
- The added legacy source round-trip test first exposed the required-child regression;
  the adapter now preserves only existing recognition leaves, and the rerun passed.
- No new Studio APK, device install or online publication was performed.

## Subsequent Release Authorization

The user subsequently requested packaging and online publication of this batch as
Studio 0.1.13 / versionCode 14. Reuse the successful focused checks, build the
development-signed optimized full/V4 ARM64 and x86_64 packages, then publish via
the existing signed HTTP protocol. The standing development-history authorization
permits recoverable archival of the oldest history only when capacity requires it.
Device installation and service configuration changes remain out of scope.

## Online Publication Result

Both development-signed optimized full/V4 0.1.13 / 14 packages passed packaging
checks and were published via the existing signed HTTP interface:

- ARM64: 36,430,278 bytes, SHA-256
  `e761664e6abfabad3e6b7c9c77314fb5afe7ee90fd4e4f4ab073be2ff15fc3f8`.
- x86_64: 51,684,022 bytes, SHA-256
  `f22268a18725520a48d61ef30b7bd63e84c7b5d03c907db8b683a8c8ecfa95a9`.

The public no-store manifest matched signed revision
`1d243c9c38b7ae998dd25a35c71a0318bfb6242803c8d8e8ee7aa737aa7ee049`.
Both complete public APK downloads matched exact sizes, digests and MIME types;
the download page returned HTTP 200 and the final signed revision remained stable.
The public inventory has eight packages totaling 352,424,276 bytes.

Only the previewed oldest 0.1.9 architecture pair was recoverably archived as
`archive-20261025T140940Z-eab974c355a0b0a2` under the standing development-history
authorization. Versions 0.1.10, 0.1.11, 0.1.12 and all prior private archives remain.
No device installation, system/server configuration change or new device
acceptance was performed.
