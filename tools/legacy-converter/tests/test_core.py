"""Synthetic fixtures only: no user archives, names, scripts, or device identities."""
import json
import pathlib
import struct
import subprocess
import tempfile
import unittest
import uuid
import warnings
import zipfile
import zlib

from legacy_converter.core import Converter, LegacyArchive, bmp_png, fields, record_lines, tree


def record(**values):
    return "".join(key + ":" + str(value) + "┊" for key, value in values.items())


def bmp(width=1, height=1, bits=32, pixels=b"\x03\x02\x01\x00"):
    stride = ((width * bits + 31) // 32) * 4
    header = (b"BM" + struct.pack("<IHHI", 54 + stride * abs(height), 0, 0, 54)
              + struct.pack("<IiiHHIIiiII", 40, width, height, 1, bits, 0,
                            stride * abs(height), 0, 0, 0, 0))
    return header + pixels


class LegacyProjectTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = pathlib.Path(self.temp.name) / "sample.zip"

    def tearDown(self):
        self.temp.cleanup()

    def archive(self, entries):
        with zipfile.ZipFile(self.path, "w") as output:
            for name, content in entries.items():
                output.writestr("synthetic/" + name, content)
        return LegacyArchive(self.path)

    def test_crlf_records_preserve_literal_lf_and_colon(self):
        lines = record_lines("_内容:hello\nworld:part┊@\r\n_内容:next┊")
        self.assertEqual(2, len(lines))
        self.assertEqual("hello\nworld:part", fields(lines[0])["_内容"])

    def test_duplicate_fields_rejected(self):
        with self.assertRaises(ValueError):
            fields("_步骤类型:a┊_步骤类型:b┊")

    def test_tree_preserves_branches_and_rejects_indent_jump(self):
        nodes = tree("_步骤类型:root┊\r\n\t_显示:success┊\r\n\t\t_内容:nested┊\r\n\t_显示:failure┊")
        self.assertEqual(2, len(nodes[0].children))
        self.assertEqual("nested", nodes[0].children[0].children[0].values["_内容"])
        with self.assertRaises(ValueError):
            tree("_内容:a┊\r\n\t\t_内容:b┊")

    def test_archive_traversal_duplicate_and_symlink_rejected(self):
        for name in ("../evil", "folder/../../evil", "folder\\evil", "folder:evil"):
            with self.subTest(name=name):
                with zipfile.ZipFile(self.path, "w") as output:
                    output.writestr("synthetic/变量信息.bl", "")
                    info = zipfile.ZipInfo("placeholder")
                    # ZipInfo normalizes the Windows separator in its constructor;
                    # construct an untrusted raw ZIP filename instead.
                    info.filename = "synthetic/" + name
                    output.writestr(info, "x")
                with self.assertRaises(ValueError):
                    LegacyArchive(self.path)
        with zipfile.ZipFile(self.path, "w") as output:
            info = zipfile.ZipInfo("synthetic/link")
            info.create_system = 3
            info.external_attr = 0o120777 << 16
            output.writestr(info, "outside")
        with self.assertRaises(ValueError):
            LegacyArchive(self.path)
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.path, "w") as output:
                output.writestr("synthetic/变量信息.bl", "")
                output.writestr("synthetic/插件/main.cj", "")
                output.writestr("synthetic/插件/main.cj", "")
        with self.assertRaises(ValueError):
            LegacyArchive(self.path)

    def test_bmp_unused_alpha_is_not_transparency_and_crc_matches(self):
        png = bmp_png(bmp())
        self.assertEqual(b"\x89PNG\r\n\x1a\n", png[:8])
        offset = 8
        payload = b""
        while offset < len(png):
            size = struct.unpack_from(">I", png, offset)[0]
            kind = png[offset + 4:offset + 8]
            data = png[offset + 8:offset + 8 + size]
            self.assertEqual(zlib.crc32(kind + data), struct.unpack_from(">I", png, offset + 8 + size)[0])
            if kind == b"IDAT":
                payload += data
            offset += 12 + size
        self.assertEqual(b"\x00\x01\x02\x03", zlib.decompress(payload))
        self.assertEqual(png, bmp_png(bmp(height=-1)))

    def test_bmp_bottom_up_and_row_padding(self):
        png = bmp_png(bmp(height=2, bits=24, pixels=b"\x03\x02\x01\x00\x06\x05\x04\x00"))
        offset = 8
        data = b""
        while offset < len(png):
            size = struct.unpack_from(">I", png, offset)[0]
            if png[offset + 4:offset + 8] == b"IDAT":
                data += png[offset + 8:offset + 8 + size]
            offset += 12 + size
        self.assertEqual(b"\x00\x04\x05\x06\x00\x01\x02\x03", zlib.decompress(data))

    def test_truncated_or_compressed_bmp_rejected(self):
        with self.assertRaises(ValueError):
            bmp_png(bmp()[:-1])
        compressed = bytearray(bmp())
        struct.pack_into("<I", compressed, 30, 3)
        with self.assertRaises(ValueError):
            bmp_png(bytes(compressed))

    def test_converts_repeat_sleep_assign_and_single_finger_without_executing(self):
        variable = record(_变量名称="count", _变量范围="_当前工程", _变量类型="_整数类型")
        content = "\r\n".join([
            record(_步骤类型="_语法", _变量操作="_变量赋值", _赋值方式="=", _变量名称="count", _赋值为="2"),
            record(_步骤类型="_循环", _循环类型="_限次循环", _循环时间="2"),
            "\t" + record(_步骤类型="_按键", _触摸类型="_单击", _触摸位置="(10,20,0)"),
            "\t" + record(_循环尾="循环尾", _显示="循环尾"),
            record(_步骤类型="_循环", _循环类型="_空循环", _循环时间="15"),
        ])
        archive = self.archive({"变量信息.bl": variable, "插件/main.cj": content})
        try:
            converter = Converter(archive)
            self.assertEqual("integer", converter.declarations()[0]["type"])
            nodes = converter.convert_flow("插件/main.cj", tree(content))
            self.assertEqual(["variable.set", "control.repeat", "input.tap", "task.sleep"], [n["kind"] for n in nodes])
            self.assertEqual(nodes[1]["nodeId"], nodes[2]["parentId"])
            self.assertEqual(nodes[1]["childBlocks"]["body"], nodes[2]["blockId"])
            self.assertFalse(converter.blockers)
        finally:
            archive.close()

    def test_unknown_function_and_success_branch_remain_blocking_not_noop(self):
        content = "\r\n".join([record(_步骤类型="_插件", _路径="/main.cj"),
                                  "\t" + record(_显示="运行成功"),
                                  "\t\t" + record(_步骤类型="_语法", _函数名字="external", _函数参数="private"),
                                  "\t" + record(_显示="运行失败")])
        archive = self.archive({"变量信息.bl": "", "插件/main.cj": content})
        try:
            converter = Converter(archive)
            nodes = converter.convert_flow("插件/main.cj", tree(content))
            self.assertEqual(4, len(nodes))
            self.assertTrue(all(n["kind"] == "legacy.unsupported" for n in nodes))
            self.assertEqual("private", nodes[2]["args"]["legacy"]["_函数参数"])
        finally:
            archive.close()

    def test_find_result_object_not_misrepresented_as_image(self):
        archive = self.archive({"变量信息.bl": record(_变量名称="match", _变量范围="_当前工程", _变量类型="_寻图类型"),
                                "插件/main.cj": ""})
        try:
            converter = Converter(archive)
            self.assertEqual([], converter.declarations())
            self.assertEqual(1, len(converter.blockers))
        finally:
            archive.close()

    def test_nonzero_finger_and_zero_drag_duration_blocked(self):
        content = "\r\n".join([
            record(_步骤类型="_按键", _触摸类型="_单击", _触摸位置="(10,20,1)"),
            record(_步骤类型="_按键", _触摸类型="_拖动", _触摸位置="(10,20,0)(30,40,0)"),
        ])
        archive = self.archive({"变量信息.bl": "", "插件/main.cj": content})
        try:
            nodes = Converter(archive).convert_flow("插件/main.cj", tree(content))
            self.assertTrue(all(n["kind"] == "legacy.unsupported" for n in nodes))
        finally:
            archive.close()

    def test_conversion_does_not_overwrite_and_withholds_backup_for_blockers(self):
        archive = self.archive({"变量信息.bl": "", "插件/main.cj": "", "界面/window.pz": "private"})
        output = pathlib.Path(__file__).resolve().parents[1] / "output" / ("test-" + uuid.uuid4().hex)
        try:
            converter = Converter(archive)
            summary = converter.convert(output, "main.cj")
            self.assertEqual("blocked", summary["status"])
            self.assertFalse((output / "candidate.asproject").exists())
            self.assertEqual(self.path.read_bytes(), (output / "original.zip").read_bytes())
            manifest = json.loads((output / "project-draft/project.json").read_text(encoding="utf-8"))
            source = (output / "project-draft" / manifest["flows"][0]["path"]).read_text(encoding="utf-8")
            self.assertEqual("legacy.unsupported", json.loads(source)["kind"])
            with self.assertRaises(ValueError):
                Converter(archive).convert(output, "main.cj")
        finally:
            archive.close()
        # Outputs deliberately remain under ignored build/ for verification; never remove user data.

    def test_scoped_variable_aliases_and_assignment_copy(self):
        variables = "\r\n".join([
            record(_变量名称="次数", _变量范围="_当前工程", _变量类型="_整数类型"),
            record(_变量名称="次数", _变量范围="main.cj", _变量类型="_整数类型"),
            record(_变量名称="result", _变量范围="main.cj", _变量类型="_整数类型"),
        ])
        content = record(_步骤类型="_语法", _变量操作="_变量赋值", _赋值方式="=", _变量名称="result", _赋值为="次数")
        archive = self.archive({"变量信息.bl": variables, "插件/main.cj": content})
        try:
            converter = Converter(archive)
            self.assertEqual(3, len(converter.declarations()))
            self.assertEqual("flow", converter.variable("插件/main.cj", "次数")["scope"])
            nodes = converter.convert_flow("插件/main.cj", tree(content))
            self.assertEqual("variable.copy", nodes[0]["kind"])
            self.assertEqual(converter.variable("插件/main.cj", "次数")["name"], nodes[0]["args"]["sourceName"])
        finally:
            archive.close()

    def test_candidates_compile_and_blocked_drafts_fail_closed_with_current_rust(self):
        repo = pathlib.Path(__file__).resolve().parents[3]
        checker = repo / "rust" / "target" / "debug" / "examples" / "validate_project.exe"
        if not checker.is_file():
            self.skipTest("build flow-compiler validate_project example for end-to-end validation")
        for unknown in (False, True):
            with self.subTest(unknown=unknown):
                variable = record(_变量名称="count", _变量范围="_当前工程", _变量类型="_整数类型")
                content = "\r\n".join([
                    record(_步骤类型="_语法", _变量操作="_变量赋值", _赋值方式="=", _变量名称="count", _赋值为="2"),
                    record(_步骤类型="_循环", _循环类型="_限次循环", _循环时间="2"),
                    "\t" + record(_步骤类型="_按键", _触摸类型="_单击", _触摸位置="(10,20,0)"),
                    "\t" + record(_步骤类型="_循环", _循环类型="_空循环", _循环时间="count"),
                    "\t" + record(_循环尾="循环尾", _显示="循环尾"),
                    record(_步骤类型="_运行提示", _提示类型="_弹出提示", _内容='"hello"'),
                ])
                if unknown:
                    content += "\r\n" + record(_步骤类型="_语法", _函数名字="external")
                archive = self.archive({"变量信息.bl": variable, "插件/main.cj": content, "图片/a.bmp": bmp()})
                output = repo / "build" / "legacy-conversion" / ("test-" + uuid.uuid4().hex)
                try:
                    summary = Converter(archive).convert(output, "main.cj")
                    checked = subprocess.run([str(checker), str(output / "project-draft")],
                                             capture_output=True, text=True, timeout=30)
                    self.assertEqual(2 if unknown else 0, checked.returncode, checked.stdout + checked.stderr)
                    status = json.loads(checked.stdout)
                    self.assertEqual(0, status["structuralErrors"])
                    self.assertEqual(not unknown, status["compiled"])
                    self.assertEqual(not unknown, (output / "candidate.asproject").exists())
                    self.assertEqual("blocked" if unknown else "candidate-needs-compiler-validation", summary["status"])
                finally:
                    archive.close()

    def test_original_archive_is_an_immutable_snapshot(self):
        archive = self.archive({"变量信息.bl": "", "插件/main.cj": ""})
        original = self.path.read_bytes()
        try:
            self.path.write_bytes(b"changed after open")
            self.assertEqual(original, archive.raw_bytes)
            self.assertEqual("", archive.text("插件/main.cj"))
        finally:
            archive.close()

    def test_variable_count_rejects_513_before_publishing(self):
        variables = "\r\n".join(record(_变量名称=f"v{index}", _变量范围="_当前工程", _变量类型="_整数类型")
                                  for index in range(513))
        archive = self.archive({"变量信息.bl": variables, "插件/main.cj": ""})
        try:
            with self.assertRaises(ValueError):
                Converter(archive).declarations()
        finally:
            archive.close()


if __name__ == "__main__":
    unittest.main()
