"""Standalone workflow tests use synthetic projects, never private user input."""
import importlib.util
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest
import zipfile
from unittest import mock

from legacy_converter.core import Cancelled, Converter, Job, Unmapped, publish_directory, tree
from legacy_converter.report import write_html
from legacy_converter.validation import compiler_gate
import test_core

record = test_core.record


class MappingTests(test_core.LegacyProjectTests):
    # Reuse only fixture helpers, not the inherited test suite.
    def numeric_project(self, content=""):
        variables = "\r\n".join([
            record(_变量名称="次数", _变量范围="_当前工程", _变量类型="_整数类型"),
            record(_变量名称="count", _变量范围="_当前工程", _变量类型="_整数类型"),
            record(_变量名称="ratio", _变量范围="_当前工程", _变量类型="_浮点类型"),
        ])
        archive = self.archive({"变量信息.bl": variables, "插件/main.cj": content})
        self.addCleanup(archive.close)
        converter = Converter(archive)
        converter.declarations()
        return converter

    def test_if_chain_preserves_children_and_typed_conditions(self):
        content = "\r\n".join([
            record(_步骤类型="_语法", _关键字="_IF", _判断式="次数 == count"),
            "\t" + record(_步骤类型="_循环", _循环类型="_空循环", _循环时间="1"),
            record(_步骤类型="_语法", _关键字="_EIF", _判断式="count >= 2"),
            "\t" + record(_步骤类型="_循环", _循环类型="_空循环", _循环时间="2"),
            record(_步骤类型="_语法", _关键字="_ELSE"),
            "\t" + record(_步骤类型="_循环", _循环类型="_空循环", _循环时间="3"),
        ])
        converter = self.numeric_project(content)
        nodes = converter.convert_flow("插件/main.cj", tree(content))
        self.assertFalse(converter.blockers)
        self.assertEqual(["control.if", "task.sleep", "task.sleep", "task.sleep"], [n["kind"] for n in nodes])
        self.assertEqual("count", nodes[0]["args"]["valueVariable"])
        self.assertEqual("greaterOrEqual", nodes[0]["args"]["elseIf"][0]["operator"])
        for node, branch in zip(nodes[1:], ("then", "elseIf0", "else")):
            self.assertEqual(nodes[0]["nodeId"], node["parentId"])
            self.assertEqual(nodes[0]["childBlocks"][branch], node["blockId"])
            self.assertEqual(1, node["depth"])

    def test_invalid_else_if_blocks_entire_chain_without_dropping_children(self):
        content = "\r\n".join([
            record(_步骤类型="_语法", _关键字="_IF", _判断式="count == 1"),
            "\t" + record(_步骤类型="_循环", _循环类型="_空循环", _循环时间="1"),
            record(_步骤类型="_语法", _关键字="_EIF", _判断式="external()"),
            "\t" + record(_步骤类型="_循环", _循环类型="_空循环", _循环时间="2"),
            record(_步骤类型="_语法", _关键字="_ELSE"),
        ])
        converter = self.numeric_project(content)
        nodes = converter.convert_flow("插件/main.cj", tree(content))
        self.assertEqual(5, len(nodes))
        self.assertNotIn("control.if", [n["kind"] for n in nodes])
        self.assertEqual(3, len(converter.blockers))

    def test_arithmetic_aliases_and_rejects_non_equivalent_operators(self):
        converter = self.numeric_project()
        target = converter.variable("插件/main.cj", "count")
        expression = converter.arithmetic("插件/main.cj", "次数 * (2 + count) - 1", target)
        self.assertIn(converter.variable("插件/main.cj", "次数")["name"], expression)
        for value in ("count / 2", "count % 2", "external()", "count++", "count--", "count**2", "ratio + 1", "count + 1.5", "count + " + "9" * 400):
            with self.subTest(value=value), self.assertRaises(Unmapped):
                converter.arithmetic("插件/main.cj", value, target)

    def test_comparison_rejects_coercions_and_compound_expressions(self):
        converter = self.numeric_project()
        for value in ("count == ratio", "count == true", "count == 1 && count < 3", "count == 1.5", "external() == 1"):
            with self.subTest(value=value), self.assertRaises(Unmapped):
                converter.comparison("插件/main.cj", value)

    def test_inspection_does_not_echo_untrusted_protocol_fields(self):
        archive = self.archive({"变量信息.bl": record(_变量名称="privateName", _变量范围="privateScope",
            _变量类型="privateType"), "插件/main.cj": record(_步骤类型="privateKind", privateKey="privateValue")})
        self.addCleanup(archive.close)
        summary = json.dumps(archive.inspect())
        self.assertNotIn("private", summary)

    def test_cancelled_conversion_never_publishes_or_modifies_original(self):
        converter = self.numeric_project()
        original = self.path.read_bytes()
        output = pathlib.Path(self.temp.name) / "cancelled"
        with self.assertRaises(Cancelled):
            Converter(converter.archive, Job(cancel=lambda: True)).convert(output, "main.cj")
        self.assertFalse(output.exists())
        self.assertEqual(original, self.path.read_bytes())

    def test_mapped_if_and_calculation_compile_through_bundled_gate(self):
        repo = pathlib.Path(__file__).resolve().parents[3]
        checker = repo / "rust/target/debug/examples/validate_project.exe"
        if not checker.is_file():
            self.skipTest("compiler checker not built")
        content = "\r\n".join([
            record(_步骤类型="_语法", _变量操作="_变量赋值", _赋值方式="=", _变量名称="count", _赋值为="次数 + 2"),
            record(_步骤类型="_语法", _关键字="_IF", _判断式="count >= 2"),
            "\t" + record(_步骤类型="_循环", _循环类型="_空循环", _循环时间="1"),
            record(_步骤类型="_语法", _关键字="_ELSE"),
            "\t" + record(_步骤类型="_循环", _循环类型="_空循环", _循环时间="2"),
        ])
        converter = self.numeric_project(content)
        # convert() rebuilds declarations; do not reuse a converter after partial mapping.
        output = pathlib.Path(self.temp.name) / "external-output"
        summary = Converter(converter.archive).convert(output, "main.cj", compiler_gate(checker))
        self.assertEqual("converted", summary["status"])
        self.assertTrue((output / "project.asproject").is_file())
        self.assertFalse((output / "candidate.asproject").exists())


# Prevent inherited tests from being run twice while retaining fixture helpers.
for _name in tuple(test_core.LegacyProjectTests.__dict__):
    if _name.startswith("test_"):
        setattr(MappingTests, _name, None)


class StandaloneTests(unittest.TestCase):
    def test_job_cancellation_and_deadline(self):
        with self.assertRaises(Cancelled):
            Job(cancel=lambda: True).check()
        with self.assertRaises(TimeoutError):
            Job(timeout=-1).check()

    def test_publication_retries_permission_lock_and_never_overwrites(self):
        with tempfile.TemporaryDirectory() as directory:
            source = pathlib.Path(directory) / "pending"
            output = pathlib.Path(directory) / "output"
            source.mkdir()
            rename = pathlib.Path.rename
            calls = []

            def locked(path, target):
                calls.append(path)
                if len(calls) < 3:
                    raise PermissionError("synthetic scanner lock")
                return rename(path, target)

            with mock.patch.object(pathlib.Path, "rename", locked):
                publish_directory(source, output, Job())
            self.assertEqual(3, len(calls))
            source.mkdir()
            with self.assertRaises(FileExistsError):
                publish_directory(source, output, Job())
            self.assertTrue(source.is_dir())

    def test_html_escapes_private_record_inertly(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "report.html"
            write_html(path, {"summary": {"status": "blocked", "plugins": 1, "mappedVariables": 0,
                "convertedImages": 0, "convertedOperations": 0, "unsupportedItems": 1},
                "blockers": [{"source": "<script>source</script>", "line": 1, "reason": "<unsafe>",
                              "record": {"value": "</pre><script>alert(1)</script>"}}]})
            html = path.read_text(encoding="utf-8")
            self.assertNotIn("<script>alert(1)</script>", html)
            self.assertIn("&lt;script&gt;", html)

    def test_portable_bundle_isolated_cli_and_whitelist(self):
        tool = pathlib.Path(__file__).resolve().parents[1]
        spec = importlib.util.spec_from_file_location("tool_package", tool / "package.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            bundle = root / "tool.zip"
            module.package(bundle)
            with zipfile.ZipFile(bundle) as archive:
                names = archive.namelist()
                self.assertFalse(any("output/" in name or "tests/" in name or "__pycache__" in name for name in names))
                metadata = json.loads(archive.read("智构工程转换工具/bundle.json"))
                import hashlib
                for name, digest in metadata["sha256"].items():
                    self.assertEqual(digest, hashlib.sha256(archive.read("智构工程转换工具/" + name)).hexdigest())
                archive.extractall(root / "isolated")
            isolated = root / "isolated/智构工程转换工具"
            with zipfile.ZipFile(root / "sample.zip", "w") as archive:
                archive.writestr("synthetic/变量信息.bl", "")
                archive.writestr("synthetic/插件/main.cj", "")
            result = subprocess.run([sys.executable, "-m", "legacy_converter", str(root / "sample.zip"),
                                     "--entry", "main.cj", "--output", str(root / "converted")],
                                    cwd=isolated, capture_output=True, timeout=30)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("candidate-needs-compiler-validation", json.loads(result.stdout)["status"])
            self.assertTrue((root / "converted/report.html").is_file())
            with self.assertRaises(ValueError):
                module.package(bundle)
            with self.assertRaises(ValueError):
                module.package(root / "bad.zip", root / "missing.exe")
            self.assertFalse((root / "bad.zip").exists())
            checker = tool.parents[1] / "rust/target/debug/examples/validate_project.exe"
            if checker.is_file():
                compiled_bundle = root / "compiled-tool.zip"
                module.package(compiled_bundle, checker)
                with zipfile.ZipFile(compiled_bundle) as archive:
                    self.assertTrue(any(name.endswith("bin/validate_project.exe") for name in archive.namelist()))
                    archive.extractall(root / "compiled-isolated")
                result = subprocess.run([sys.executable, "-m", "legacy_converter", str(root / "sample.zip"),
                                         "--entry", "main.cj", "--output", str(root / "validated")],
                                        cwd=root / "compiled-isolated/智构工程转换工具",
                                        capture_output=True, timeout=30)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual("converted", json.loads(result.stdout)["status"])
                self.assertTrue((root / "validated/project.asproject").is_file())

    def test_gui_log_result_cancel_and_empty_issue_list(self):
        import tkinter as tk
        from legacy_converter.gui import ConverterWindow
        root = tk.Tk()
        root.withdraw()
        try:
            window = ConverterWindow(root)
            window.log("synthetic aggregate log")
            self.assertIn("synthetic aggregate log", window.log_view.get("1.0", "end"))
            with tempfile.TemporaryDirectory() as directory:
                output = pathlib.Path(directory)
                (output / "report.json").write_text('{"blockers": []}', encoding="utf-8")
                window.events.put(("result", {"mode": "convert", "output": str(output), "summary": {
                    "status": "converted", "plugins": 1, "mappedVariables": 0, "convertedImages": 0,
                    "convertedOperations": 0, "unsupportedItems": 0}}))
                window.pump()
                self.assertIn("编译检查通过", window.status.get())
                self.assertTrue((output / "tool.log").is_file())
            window.request_cancel()
            self.assertTrue(window.cancel.is_set())
            for index in range(900):
                window.log_lines.append(str(index))
            self.assertEqual(800, len(window.log_lines))
        finally:
            root.destroy()
