# ADR 0206: Variable calculation shortcuts and typed collection reads

Date: 2026-11-05

## Decision

Keep one `variable.calculate` node with its existing `name` and `expression` fields. Add a compact shortcut form inside its parameter dialog for assignment/conversion, arithmetic, text operations, splitting and typed list/object reads. Sources and defaults may be literals or visible variables. Filter destinations by the inferred result type; never change declarations automatically. Retain the full expression editor, old byte/character/integer tools, and complex expressions without rewriting them on open.

The form emits only the bounded expression grammar, using JSON-quoted text and canonical numeric literals. Supported shortcut forms reopen from that expression without new persistent UI metadata. Switching back from an unmodified shortcut form preserves the original complex expression. The scrollable form remains inside the existing bounded, IME-aware dialog with fixed footer; no autofocus is added.

## Semantics

- Integer conversion keeps existing truncation toward zero and exact 64-bit integer handling, including textual `+190`. `intOr`/`floatOr` supply explicit typed defaults for conversion failure; they do not catch errors while evaluating source expressions.
- Numeric helpers add absolute value, floor, ceiling, nearest rounding with halves away from zero, and min/max. Non-finite results and integer overflow remain errors.
- `split` treats its separator as one literal token; an empty separator splits Unicode characters. `splitAny` treats each Unicode character in its nonempty separator as an alternative. Optional flags 0..3 select trim and/or empty-item removal. Output is JSON string-array text, at most 1024 entries and 4096 encoded bytes, with valid control-character escaping.
- `listLen` and `itemText`/`itemInt`/`itemFloat`/`itemJson` reuse the existing bounded Rust data APIs. Arrays require integer indices starting at 1; object keys are exact text, not interpreted paths. Nested collections can be read with `itemJson` and then read again. Optional defaults cover missing/null values and numeric conversion failure. Invalid JSON, malformed keys/indexes, and nested values requested as scalar text remain errors. No broad host-error catch or new opcode is introduced.
- Collection expressions require core.task and Runtime API 1.8. Saved flows and both single/all unsaved draft paths promote through the existing mechanism. Quoted helper-like text does not promote the project.
- Scalar expressions retain the existing 1024-byte/128-node/16-depth grammar and 4096-byte string budget. No screenshot, Root, input or frame-cache behavior changes.

The boundary checks also exposed existing `variable.set` emission of JSON-only control escapes as Lua. Shared scalar/string rendering now reuses the bounded expression Lua quoting routine; normal JSON/Lua-compatible newline/tab escapes remain readable, and other control bytes use fixed three-digit Lua escapes. All 128 ASCII bytes round-trip through actual Lua.

## Validation

33 distinct targeted checks passed: 18 flow-compiler checks (existing conversion/scope/boundary/Unicode coverage plus new real RuntimeExecutor JSON reads and split/error budgets), one JNI temporary-project draft/persist/compile/engine round-trip, 13 Studio JVM checks and one ProjectStore save/reopen check. Release production and unit-test Kotlin compilation passed with the original distribution/x86_64/full configuration. Native Gradle inputs now include the Lua helper source so subsequent helper edits invalidate the compiler build.

Evidence: `build/calculation-quick-runtime-fixed.log`, `build/calculation-quick-parser-final.log`, `build/calculation-quick-escapes.log`, `build/calculation-quick-unicode.log`, `build/calculation-quick-bridge-final.log`, `build/calculation-quick-android-tests.log`, `build/calculation-quick-android-final.log`. Earlier failed fixtures and the control-escape reproduction logs are retained. No APK, device installation, private script execution, release/version change, publication, Git commit or server/secret/backup change was performed. These host checks do not claim device layout or keyboard acceptance.

## Authorized release

The subsequent explicit request to package and publish authorized Studio 0.1.37/code38. Reused the completed targeted checks, original development signing, full/V4 dual-ABI distribution builds, compact `packageRelease`, and original bidirectional HMAC HTTP publisher. Necessary signature/identity/native/model/size/alignment checks passed. Packages:

- ARM64: 36,717,026 bytes; SHA256 `276c9202c8abb2a8a2ea684f4de54b95a837429d6b27f5b5fe76bc8012d0c3bc`.
- x86_64: 51,978,314 bytes; SHA256 `b9f566401aa95884d62c8bdb8f9af100250ac160da598cd7774d1c43fd3abb9b`.

After signed preview, only the oldest 0.1.33 pair was recoverably archived under standing authorization as `archive-20261105T090836Z-747ce366506279aa`. Existing archives and other versions remain. Both new packages were published, with final signed/public raw manifest revision `d6ed36261f68f66be28420637744c326dcebef1f442a04aad6adacce50209389`; inventory is eight packages totaling 354,523,136 bytes. Public JSON/no-store, complete APK size/digest/MIME, Range/checksum files and download-page HTTP 200 passed. See `build/calculation-quick-{arm64,x86}-distribution.log`, archive/publish logs and `build/calculation-quick-online-final.log`.

No device installation or private-script execution, repeat full suite, Git commit, Root change, server configuration/secret/project-backup change, account-backend deployment or source-guide deployment was performed. Publication checks do not constitute device UI acceptance.
