# ADR 0095: Signed offline Runner components

Date: 2026-10-13. Status: accepted for local implementation.

## Decision

Runner templates are imported APK artifacts for packaging, never installed or loaded into Studio. Downloads remain out of scope. Accept only APK v2 RSA/SHA-256 signatures from the current Studio publisher certificate. Self-supplied hashes and first-use certificates do not establish trust. Debug exports use the same development signing identity as Studio; these are development artifacts, not production release packages.

Store a bounded versioned descriptor inside the signed APK at `assets/packaging/runner-component.json`. Validate fixed application/version identity, runtime protocol/API, minSdk24, supported ABIs, exact base/OpenCV/ONNX/full family, pinned dependency versions/model hash and required license files. Check the actual Android manifest with PackageManager. Imported files cannot contain frozen projects or the Studio compiler. Do not infer runtime SDK installation from a packaging template.

Local storage holds at most eight immutable content-addressed APKs and one GiB. Import uses an owned staging file, bounded copying, cancellation/deadline and signature validation before atomic rename. Existing and unknown files are not overwritten or automatically deleted. Builds copy the explicitly selected exact-family artifact into their own staging area and revalidate before use. A registry operation is serialized off the UI thread; failed/cancelled imports clean only their own staging.

Family-aware archive validation preserves existing full-template callers while enabling selectively built templates. Rebuilt Runner APKs retain device-private signing and resource freezing. Project capabilities, source, system permissions and existing installed Runner data remain unchanged.

## Limits

Certificate rotation needs a separately reviewed trust migration. Imported templates do not dynamically install OpenCV/ONNX into Studio. No arbitrary model loading, script-supplied class/native path, automatic installation or downloader is introduced. Real-device acceptance is separate from host tests and packaging validation.
