# ADR 0154: Practical editor shortcuts

Status: accepted, 2026-10-24.

Implement the user's four approved enhancements without changing project format,
runtime behavior, identities or the existing compact window style.

Function favorites (64) and successful recent insertions (24) use private,
bounded app preferences containing catalog kinds only. Opening configuration or
cancelling insertion does not count as use. No preferences enter project backups.

Project search uses a frozen set of current drafts, indexes off the UI thread,
supports name/type/variable/image filters and reports skipped or limited content.
Results are read-only. Navigation rechecks the exact source and real node, exits
view focus and detaches execution following, without saving or executing scripts.

Batch parameters affect only explicitly selected real rows of one kind, not
implicit descendants. Offer a conservative set of numeric common fields from
the generated contract; exclude variable-bound fields, identities and outputs.
Preview the affected rows. Validate the complete draft set before one undoable
commit and recheck project, manifest, all drafts, selection and runtime state
after validation. Cancellation/stale state must not partially apply changes.

Known failures receive bounded plain-language suggestions; original diagnostic
details remain available. Compiler navigation resolves real plugin/node or line
against its frozen source and does not guess a row after edits. Opening parameters
uses the existing editor handler and remains disabled while editing is unsafe.

Use targeted JVM tests and the ordinary MuMu 0 build/install workflow. No phone
connection, private script execution or online publication in this batch.

Verification, 2026-10-24: 149 targeted JVM cases in 17 classes passed. They cover
bounded preferences, actual insertion callbacks, all-draft search, stale targets,
literal-only batch fields, unchanged child/envelope data, single undo, validation
failure/cancellation and the shared prompt layer. This is not a full device UI or
phone acceptance result.

The ordinary development build succeeded and installed to MuMu 0
(`127.0.0.1:16384`) with app data retained. APK size: 100,934,089 bytes.
Default full vision and existing models remain unchanged; no online publication.
