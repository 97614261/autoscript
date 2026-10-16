# ADR 0096: Fixed offline vision providers

Date: 2026-10-13. Status: accepted direction; implementation gates tracked separately.

## Decision

Optional OpenCV and ONNX must be actual runtime components, not only different Runner templates or UI checkboxes. First publish two independent, SDK-only APK artifacts and a shared narrow vision SPI. The SPI carries borrowed bounded pixel buffers, fixed operations, cancellation and owned cleanup; it has no Root, Binder, editor, arbitrary shell, JNI pointer or Lua exposure. Reuse the existing match/preprocessing/CTC implementation without changing scores or pixels.

Provider artifacts have no exported component, Activity, Root service or project data. Their only entry classes are fixed in host code. Descriptor version1 specifies provider kind/version, SPI version, Android ABIs, minSdk, actual SDK version and pinned model/license evidence. Sharing a Java package does not establish trust. Later local import/loading must validate the same-publisher APK v2 signature, strict descriptor, actual manifest, allowlisted native libraries and model before any class/native code is loaded.

Controlled loading is limited to these two fixed publisher-signed providers. This does not authorize arbitrary Java/native plugins, model files, class names supplied by scripts or endpoints. Downloads remain excluded. The host owns a frozen private copy and typed lifetime; metadata availability is not inference success, Root support or a performance claim. Do not switch the default Studio to base until verified import/resolve/load/missing-component paths and native lifetime gates work.

Keep current embedded build profiles for comparison and explicit offline developers. Runtime SPI versioning is independent from the existing Binder protocol; no core-protocol change is required merely to publish provider artifacts. Project capability declarations remain authorization, not installation. New all-capability projects must not lose unrelated Rust operations simply because an unused optional capability was granted.

## Gates

1. Shared SPI and both provider artifacts compile; artifacts contain only their own SDK/model and licenses, no engine/Root/Studio. SPI/runtime-service tests and provider exports passed on 2026-10-13. Shared provider validator and signed rejection tests passed (ADR0097); the actual PackageManager manifest reader still needs Android device acceptance.
2. Strict same-publisher import, bounded storage, immutable extraction/native paths, Android34 read-only DEX requirement, fixed class resolution, cancellation and ownership are implemented and tested.
3. Host/service availability is queried through runtime-api/client on IO; missing and unhealthy remain distinct. Function/test/run/package requirements are not silently changed.
4. Public synthetic OpenCV/ONNX fixtures execute through the actual loaded artifacts; Android24/25 and supported ABI acceptance is recorded separately.
5. Only after these gates, default Studio may omit the SDKs/model. Old projects retain declared capabilities and data.

Artifact publication alone does not satisfy gates2-5 or the full long-term plan.
