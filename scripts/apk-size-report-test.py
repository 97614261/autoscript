"""Synthetic fixtures only; do not build or modify the retained APK."""
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import warnings
from unittest.mock import patch
import zipfile

SCRIPT = Path(__file__).with_name("apk-size-report.py")
SPEC = importlib.util.spec_from_file_location("apk_size_report", SCRIPT)
REPORT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(REPORT)


class ApkSizeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.apk = Path(self.temp.name) / "fixture.apk"
        self.model = b"public synthetic model fixture"
        self.models = {name: (len(self.model), hashlib.sha256(self.model).hexdigest()) for name in REPORT.MODELS}
        self.patcher = patch.object(REPORT, "MODELS", self.models)
        self.patcher.start()
        self.addCleanup(self.patcher.stop)

    def fixture(self, abis=("arm64-v8a",), omit=(), extra=None):
        values = {f"lib/{abi}/{library}": b"synthetic native" for abi in abis for library in REPORT.LIBRARIES}
        values.update({name: self.model for name in self.models})
        values.update({f"assets/vision/{name}": b"public fixture license" for name in REPORT.LICENSES})
        values["classes.dex"] = b"synthetic dex" * 100
        values.update(extra or {})
        with zipfile.ZipFile(self.apk, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name, content in values.items():
                if name not in omit:
                    entry = zipfile.ZipInfo(name)
                    # Windows ZipInfo normalizes backslashes; retain the adversarial input.
                    entry.filename = name
                    entry.compress_type = zipfile.ZIP_DEFLATED
                    archive.writestr(entry, content)

    def test_single_and_universal_profiles(self):
        for abis in (("arm64-v8a",), ("x86_64",), ("arm64-v8a", "x86_64")):
            self.fixture(abis)
            result = REPORT.validate_full(self.apk, abis)
            self.assertEqual(result["abis"], sorted(abis))
            self.assertEqual(result["defaultOcr"], "V4")
            self.assertEqual(result["bytes"], result["payloadBytes"] + result["layoutSigningAlignmentBytes"])

    def test_apk_is_not_rewritten(self):
        self.fixture()
        before = self.apk.read_bytes()
        REPORT.validate_full(self.apk, ["arm64-v8a"])
        self.assertEqual(before, self.apk.read_bytes())

    def test_wrong_and_duplicate_abis(self):
        self.fixture()
        for abis in (["x86_64"], ["arm64-v8a", "arm64-v8a"], ["armeabi-v7a"], []):
            with self.assertRaises(ValueError):
                REPORT.validate_full(self.apk, abis)

    def test_model_tampering_or_omission(self):
        name = next(iter(self.models))
        for extra in ({name: b"x" * len(self.model)}, {name: b"wrong size"}):
            self.fixture(extra=extra)
            with self.assertRaises(ValueError):
                REPORT.validate_full(self.apk, ["arm64-v8a"])
        self.fixture(omit=[name])
        with self.assertRaises(KeyError):
            REPORT.validate_full(self.apk, ["arm64-v8a"])

    def test_missing_license_and_native_library(self):
        for name in ("assets/vision/OpenCV-LICENSE.txt", "lib/arm64-v8a/libengine_jni.so"):
            self.fixture(omit=[name])
            with self.assertRaises((KeyError, ValueError)):
                REPORT.validate_full(self.apk, ["arm64-v8a"])

    def test_embedded_runner_and_foreign_native_rejected(self):
        for name in ("assets/packaging/runner-template.apk", "assets/packaging/runner-template.json",
                     "lib/arm64-v8a/foreign.so"):
            self.fixture(extra={name: b"fixture"})
            with self.assertRaises(ValueError):
                REPORT.validate_full(self.apk, ["arm64-v8a"])

    def test_layout_budget_does_not_delete_candidate(self):
        self.fixture()
        with self.apk.open("ab") as stream:
            stream.write(b"padding" * 2000)
        before = self.apk.read_bytes()
        with self.assertRaisesRegex(ValueError, "layout budget"):
            REPORT.validate_full(self.apk, ["arm64-v8a"], maximum_layout_bytes=4096)
        self.assertEqual(before, self.apk.read_bytes())

    def test_duplicate_entries_rejected(self):
        self.fixture()
        with warnings.catch_warnings(), zipfile.ZipFile(self.apk, "a") as archive:
            warnings.simplefilter("ignore", UserWarning)
            archive.writestr("classes.dex", b"duplicate")
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            REPORT.inspect_apk(self.apk)

    def test_unsafe_names_rejected(self):
        for name in ("../foreign", "/foreign", "assets\\foreign"):
            with self.subTest(name=name):
                self.fixture(extra={name: b"fixture"})
                with self.assertRaisesRegex(ValueError, "Unsafe"):
                    REPORT.inspect_apk(self.apk)

    def test_cli_cannot_overwrite_package(self):
        self.fixture()
        before = self.apk.read_bytes()
        result = subprocess.run([sys.executable, str(SCRIPT), "--apk", str(self.apk),
                                 "--abis", "arm64-v8a", "--output", str(self.apk),
                                 "--mapping", str(self.apk), "--seeds", str(self.apk)],
                                capture_output=True, timeout=10)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(b"Report output must not overwrite", result.stderr)
        self.assertEqual(before, self.apk.read_bytes())

    def test_report_is_json_serializable(self):
        self.fixture()
        report = json.loads(json.dumps(REPORT.inspect_apk(self.apk)))
        self.assertEqual(report["sha256"], REPORT.file_digest(self.apk))
        self.assertGreater(report["groups"]["models"]["apkBytes"], 0)

    def test_r8_fixed_classes_and_native_callbacks(self):
        mapping = Path(self.temp.name) / "mapping.txt"
        seeds = Path(self.temp.name) / "seeds.txt"
        lines = [f"{name} -> {name}:" for name in REPORT.FIXED_CLASSES]
        callbacks = [f"com.autoscript.runtime.service.NativeWakeListener: void {method}()"
                     for method in ("onNativeWake", "onNativeRootDisconnected")]
        mapping.write_text("\n".join(lines), encoding="utf-8")
        seeds.write_text("\n".join(callbacks), encoding="utf-8")
        REPORT.validate_r8(mapping, seeds)
        seeds.write_text(callbacks[0], encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "onNativeRootDisconnected"):
            REPORT.validate_r8(mapping, seeds)
        seeds.write_text("\n".join(callbacks), encoding="utf-8")
        mapping.write_text("\n".join(lines[1:]), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "removed or renamed"):
            REPORT.validate_r8(mapping, seeds)


if __name__ == "__main__":
    unittest.main()
