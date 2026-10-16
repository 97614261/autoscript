# Offline Runner components: 2026-10-13

Scope: ADR0095 local packaging templates, not SDK runtime installation or a downloader.

## Implemented

- Pristine Runner builds generate a signed-inner descriptor with component/version, protocol25, API1.8, minSdk24, family, ABIs, dependency versions, license paths and pinned OCR model hash.
- Explicit `exportRunnerComponent` preserves the unsigned test template and signs a separate offline development artifact with the existing Studio development publisher identity. No project scripts are included.
- Local import verifies APK v2 publisher, descriptor, actual PackageManager manifest, exact library family, ELF architecture, model content and license presence. Duplicate JSON keys, trailing documents and oversized descriptors are rejected.
- Private immutable SHA-256 inventory: ready/incompatible/corrupt; maximum8 files/1GiB. Unknown files count against quota and are preserved. Explicit confirmed deletion touches only the selected content-addressed artifact; no automatic cleanup of old components.
- Import has one bounded worker/one queued request,120-second timeout, document-provider cancellation and owned descriptor closure. Only owned staging is cleaned. Project/screen disposal cancels the request; late results do not launch an editor or script.
- Project package screen offers import, refresh, cancel, explicit exact-family selection and deletion confirmation. Packaging copies and revalidates the selected artifact before archive rebuilding, preserving device-private output signing and frozen project checks.
- Library gates corrected using actual packages: `libc++_shared.so` belongs to CV/full, not ONNX-only; Compose's `libandroidx.graphics.path.so` is an allowed shared runtime library. Other unknown native entries are rejected.
- Real SDK-export matrix exposed a verifier compatibility gap: SDK35 V2SchemeSigner appends a fourth empty length-prefixed signed-data field. The verifier now accepts exactly that empty field as well as the existing three-field form. Unknown/nonempty extensions remain rejected; RSA and content digest verification are unchanged. Evidence is the installed SDK signer bytecode and actual exports, not an assumption about arbitrary signature attributes.

## Real exported artifacts

All are development templates with arm64-v8a+x86_64, Android24 minimum, fixed `com.zhigou.yunxingban` identity and the same certificate as the installed Studio development APK. These are APK total byte observations, not estimated SDK savings.

| Family | APK bytes | SDK/model contents |
|---|---:|---|
| base |30,384,661|Rust/core only|
| opencv |108,614,545|OpenCV4.10 + C++ shared, no ONNX/model|
| onnx |75,646,096|ONNX Runtime1.19.2 + pinned model, no OpenCV/C++ shared|
| full |153,880,076|Both SDKs + pinned model|

`scripts/verify-runner-components.ps1` used independent SDK apksigner/aapt2 to check all four against Studio's certificate and actual Android manifest, native entry sets, license presence and model hash. All four passed. Exact hashes/report are local generated evidence in `build/component-audit/development/runner-components.json`.

## Verification boundary

Host tests cover duplicate import, freeze, wrong family/manifest/publisher, tamper, cancellation cleanup, unknown-file quota and explicit removal. Descriptor tests cover version/protocol/ABI, duplicate/unknown keys, nesting, size and trailing JSON. Compiled full Runner validation uses actual ELF/model/license contents; matrix test is enabled explicitly after all four offline exports exist.

Explicit `-Pautoscript.verifyComponentMatrix=true` host run passed all645 Studio tests, zero failures/errors/skips, including all four real SDK-signed exports through the production component validator. Normal verification has the same645 cases with the matrix-only case explicitly skipped when the property is absent. This does not replace the PackageManager/SAF device acceptance boundary.

Normal `build.cmd -Verify` passed tests, Lint and Debug build, and installed Studio into MuMu0 with data retained. Lint has no errors;35 warnings and16 hints remain, including conservative `usableSpace` advisories. This implementation does not reclaim system caches or depend on API26-only allocation APIs on Android24.

Final installed development APK:176,170,281 bytes; compressed ZIP payload172,153,727; layout/signing/padding4,016,554. Native stripping/ABI checks and pinned model hash passed; no Runner asset is embedded. Default Studio still contains the full vision SDK/model profile until the separate runtime availability/default-profile stage is delivered.

Not claimed: SAF import interaction or device-side packaging acceptance, ARM-only artifact matrix, Android24/25 devices, Root script performance, OCR accuracy changes, dynamic SDK/model installation. Existing lowercase l/1 OCR failure remains in the earlier public fixture report. No private user script was run.
