# ADR 0094: Optional vision and independent Runner artifacts

Date: 2026-10-13. Status: accepted direction; implementation tracked separately.

## Decision

Runner template distribution is independent from Studio. OpenCV and ONNX/model dependencies are optional components. No downloader is implemented in this stage; only local availability and a stable bounded descriptor/resolution boundary may be provided. Missing components explicitly block their operation, not unrelated Rust image operations.

Keep current frame handles, task ownership, contract names and project capability truth. Separate the fixed JNI host facade from the two implementations. OpenCV is not an OCR preprocessing prerequisite after the decoupling stage; BGR layout/interpolation/normalization must be verified before accepting this change.

Build component combinations with isolated Gradle output/cache, just as ABI profiles are isolated. Runner artifacts include kind/version/ABI/minSdk/runtime compatibility, bounded size, hash and license evidence. Runtime loading never accepts arbitrary script-supplied Java/native paths. Acquisition remains out of scope.

Installed components, script capability declarations and system permissions are not interchangeable. Preserve old declarations and clearly show requested-but-missing dependencies. Existing projects never silently change matching algorithms, score definitions or OCR models.

## Delivery gates

Implementation checkpoint: SDK-neutral JNI facade dispatches through fixed generated factories into separate OpenCV/ONNX source sets, with common cancellation and ownership. Explicit component builds use `build/profiles/components/<abiProfile>/<visionProfile>` and require its absolute `.gradle` cache path. Default stays full until availability preflight is delivered. Independent BGR preprocessing passed28 exact-tensor synthetic comparisons against OpenCV4.10 on MuMu API32; ONNX-only no longer includes or calls OpenCV. This is not ARM device/Android24 acceptance or delivery of local component acquisition/selection.

See [execution plan](../reference/optional-vision-runner-execution-plan.md). A component is not delivered because a checkbox or descriptor exists; the actual native implementation, selective artifact and corresponding visual/runtime validation must be present. Base/CV-only/ONNX-only/full profile contents require audit. No numerical package-saving or performance promise is made before measurement.
