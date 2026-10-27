"""Inspect APK size/inventory without rewriting any signed package."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

MIB = 1024 * 1024
MODELS = {
    "assets/vision/en_PP-OCRv4_rec_mobile.onnx": (
        7653044, "e8770c967605983d1570cdf5352041dfb68fa0c21664f49f47b155abd3e0e318"),
    "assets/vision/en_PP-OCRv5_rec_mobile.onnx": (
        7872351, "c3461add59bb4323ecba96a492ab75e06dda42467c9e3d0c18db5d1d21924be8"),
}
LIBRARIES = {
    "libengine_jni.so", "libstudio_compiler_jni.so", "libautoscript_root_daemon.so",
    "libopencv_java4.so", "libonnxruntime.so", "libonnxruntime4j_jni.so",
    "libc++_shared.so", "libandroidx.graphics.path.so",
}
LICENSES = (
    "NOTICE.txt", "OpenCV-LICENSE.txt", "ONNXRuntime-LICENSE.txt", "PaddleOCR-LICENSE.txt")
FIXED_CLASSES = (
    "org.opencv.core.Mat",
    "org.opencv.core.Core",
    "org.opencv.imgproc.Imgproc",
    "org.opencv.features2d.ORB",
    "ai.onnxruntime.OrtEnvironment",
    "ai.onnxruntime.OrtSession",
    "ai.onnxruntime.OrtSession$Result",
    "ai.onnxruntime.OnnxTensor",
    "com.autoscript.engine.jni.NativeEngineBridge",
    "com.autoscript.runtime.service.RootInputBridge",
    "com.autoscript.runtime.service.RootCaptureBridge",
    "com.autoscript.runtime.service.NativeVisionAdapter",
    "com.autoscript.runtime.service.NativeScriptUiAdapter",
    "com.autoscript.runtime.service.NativeDataFileAdapter",
    "com.autoscript.runtime.service.NativeVisionBackend",
    "com.autoscript.runtime.service.NativeVisionProvider",
    "com.autoscript.runtime.service.NativeVisionModelSource",
    "com.autoscript.runtime.service.NativeVisionModelSet",
    "com.autoscript.runtime.service.NativeVisionWork",
    "com.autoscript.runtime.service.VisionBuffersKt",
    "com.autoscript.runtime.service.OnnxImagePreprocessingKt",
    "com.autoscript.runtime.service.OnnxImageInput",
    "com.autoscript.runtime.service.VisionRect",
    "com.autoscript.project.store.ProjectManifestDocument",
    "com.autoscript.project.store.PopupStyle",
    "com.autoscript.script.ui.UiPage",
    "com.autoscript.script.ui.UiPresentation",
    "com.autoscript.studio.UiDesignerCheckpoint",
    "com.autoscript.studio.RunnerUiDesignerDraft",
)


def stream_digest(stream):
    digest = hashlib.sha256()
    for block in iter(lambda: stream.read(MIB), b""):
        digest.update(block)
    return digest.hexdigest()


def file_digest(path):
    with path.open("rb") as stream:
        return stream_digest(stream)


def inspect_apk(path):
    size = path.stat().st_size
    if not 0 < size <= 512 * MIB:
        raise ValueError("APK size budget exceeded")
    groups = {}
    largest = []
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        if not 1 <= len(entries) <= 10000:
            raise ValueError("APK entry budget exceeded")
        names = [entry.filename for entry in entries]
        if len(names) != len(set(names)):
            raise ValueError("Duplicate APK entries")
        for entry in entries:
            name = entry.filename
            parts = name.split("/")
            if entry.orig_filename != name or name.startswith("/") or "\\" in name or ".." in parts:
                raise ValueError("Unsafe APK entry")
            group = ("/".join(parts[:2]) if name.startswith("lib/") else
                     "dex" if name.endswith(".dex") else
                     "models" if name in MODELS else
                     "resources" if name.startswith("res/") or name == "resources.arsc" else "other")
            value = groups.setdefault(group, {"entries": 0, "rawBytes": 0, "apkBytes": 0})
            value["entries"] += 1
            value["rawBytes"] += entry.file_size
            value["apkBytes"] += entry.compress_size
            largest.append({"name": name, "apkBytes": entry.compress_size, "rawBytes": entry.file_size})
        payload = sum(entry.compress_size for entry in entries)
        if payload > size:
            raise ValueError("Invalid APK payload accounting")
    return {"formatVersion": 1, "apk": str(path.resolve()), "sha256": file_digest(path),
            "bytes": size, "payloadBytes": payload, "layoutSigningAlignmentBytes": size - payload,
            "groups": groups, "largest": sorted(largest, key=lambda item: item["apkBytes"], reverse=True)[:20]}


def validate_full(path, abis, maximum_layout_bytes=2 * MIB):
    expected = set(abis)
    if not expected or not expected <= {"arm64-v8a", "x86_64"} or len(abis) != len(expected):
        raise ValueError("Unsupported or duplicate ABI selection")
    report = inspect_apk(path)
    if report["layoutSigningAlignmentBytes"] > maximum_layout_bytes:
        raise ValueError("Compact APK layout budget exceeded; retain candidate and rebuild packaging")
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        native = {name for name in names if name.startswith("lib/") and not name.endswith("/")}
        if native != {f"lib/{abi}/{library}" for abi in expected for library in LIBRARIES}:
            raise ValueError("Native library/ABI inventory differs from full profile")
        if "assets/packaging/runner-template.apk" in names or "assets/packaging/runner-template.json" in names:
            raise ValueError("Unexpected embedded Runner")
        for name, (size, digest) in MODELS.items():
            if archive.getinfo(name).file_size != size:
                raise ValueError("Fixed OCR model size differs")
            with archive.open(name) as stream:
                if stream_digest(stream) != digest:
                    raise ValueError("Fixed OCR model digest differs")
        for name in LICENSES:
            entry = archive.getinfo(f"assets/vision/{name}")
            if not 1 <= entry.file_size <= MIB:
                raise ValueError("Missing or invalid visual license")
    report.update(abis=sorted(expected), visionProfile="full", defaultOcr="V4",
                  candidate="development-signed Release (signature verified separately)",
                  compactLayoutBudgetBytes=maximum_layout_bytes)
    return report


def validate_r8(mapping, seeds):
    for path, limit in ((mapping, 128 * MIB), (seeds, 4 * MIB)):
        if not 0 < path.stat().st_size <= limit:
            raise ValueError("R8 evidence size budget exceeded")
    required = {f"{name} -> {name}:" for name in FIXED_CLASSES}
    mapped = set()
    with mapping.open(encoding="utf-8") as stream:
        for line in stream:
            if not line.startswith((" ", "#")) and line.rstrip() in required:
                mapped.add(line.rstrip())
    kept = set(seeds.read_text(encoding="utf-8").splitlines())
    for name in FIXED_CLASSES:
        if f"{name} -> {name}:" not in mapped:
            raise ValueError(f"Fixed JNI/SPI/persistence class removed or renamed: {name}")
    for method in ("onNativeWake", "onNativeRootDisconnected"):
        if f"com.autoscript.runtime.service.NativeWakeListener: void {method}()" not in kept:
            raise ValueError(f"Native callback not retained: {method}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--abis", required=True)
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--mapping", type=Path, required=True)
    parser.add_argument("--seeds", type=Path, required=True)
    args = parser.parse_args()
    for protected in (args.apk, args.baseline):
        if protected and (args.output.resolve() == protected.resolve() or
                          (args.output.exists() and args.output.samefile(protected))):
            parser.error("Report output must not overwrite an APK")
    if args.output.is_symlink():
        parser.error("Redirected report output refused")
    report = validate_full(args.apk, args.abis.split(","))
    validate_r8(args.mapping, args.seeds)
    report["r8ContractEvidence"] = {"mappingSha256": file_digest(args.mapping),
                                   "seedsSha256": file_digest(args.seeds)}
    if args.baseline:
        baseline = inspect_apk(args.baseline)
        report["baseline"] = {key: baseline[key] for key in ("apk", "sha256", "bytes")}
        report["savedBytes"] = baseline["bytes"] - report["bytes"]
        report["savedPercent"] = round(100 * report["savedBytes"] / baseline["bytes"], 2)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"APK: {report['bytes'] / MIB:.2f} MiB; layout/signing/alignment: "
          f"{report['layoutSigningAlignmentBytes'] / MIB:.3f} MiB; SHA256: {report['sha256']}")
    if "savedBytes" in report:
        print(f"Versus retained baseline: {report['savedBytes'] / MIB:.2f} MiB smaller ({report['savedPercent']}%)")


if __name__ == "__main__":
    main()
