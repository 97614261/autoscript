# ADR 0097: Shared vision component verification

Date: 2026-10-13. Status: accepted implementation of ADR0096.

## Decision

Add the real `android:vision-components` library for host-side verification and,
in following batches, private component storage/loading. It depends on the narrow
vision SPI and the existing Gson 2.11 dependency (already used in this repository),
not Studio, Root, Lua, JNI or either SDK. Provider APKs depend only on vision-spi,
not this host library.

Move the existing APK v2 RSA/SHA256 verifier from Studio into this shared library;
Studio's signer delegates verification to it and retains private-key signing in
Studio only. Keep signer tests and signed APK fixture tests. Do not add a second
trust implementation or trust certificates supplied inside descriptors.

The provider validator checks the current publisher's v2 certificate, strict
bounded signed descriptor, actual PackageManager manifest, exact native/asset
allowlists, ELF machine identity, fixed model content hash and license budgets.
Host tests use real signed public component artifacts, not private scripts.
Artifact identity callbacks allow host JVM tests but the Android caller must use
the actual manifest reader, not self-reported descriptor fields.

No DEX/native loading is introduced by this validator batch. Import, read-only
private extraction, lifecycle, availability IPC and device tests remain separate
gates. Default Studio stays full until those gates pass. No downloads or arbitrary
code/model loading are introduced.
