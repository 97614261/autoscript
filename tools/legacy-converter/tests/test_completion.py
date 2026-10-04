"""Synthetic evidence-backed organization, variable and loop migration tests."""
import json
import pathlib
import unittest

import test_core
from legacy_converter.core import Converter, LegacyArchive, Unmapped, tree
from legacy_converter.diagnostics import inspect_issue
from legacy_converter.validation import compiler_gate

record = test_core.record


class CompletionTests(unittest.TestCase):
    def setUp(self):
        self.fixture = test_core.LegacyProjectTests()
        self.fixture.setUp()
        self.addCleanup(self.fixture.tearDown)
        self.directory = pathlib.Path(self.fixture.temp.name)

    def archive(self, entries):
        archive = self.fixture.archive(entries)
        self.addCleanup(archive.close)
        return archive

    def test_groups_and_empty_real_image_folders_are_portable(self):
        archive = self.archive({"变量信息.bl": "", "插件/main.cj": "", "插件/worker.cj": "",
            "插件/插件分组.pz": "分类A|main|worker\r\n空组\r\n",
            "图片/小 图/a.bmp": test_core.bmp(), "图片/empty/": ""})
        output = self.directory / "converted"
        summary = Converter(archive).convert(output, "main.cj")
        self.assertEqual(2, summary["convertedGroups"])
        self.assertEqual(2, summary["convertedImageDirectories"])
        self.assertEqual(0, summary["unsupportedItems"])
        root = output / "project-draft"
        group = json.loads((root / ".studio/source-groups.json").read_text(encoding="utf-8"))
        self.assertEqual(2, len(group["groups"][0]["flowIds"]))
        self.assertEqual([], group["groups"][1]["flowIds"])
        dirs = json.loads((root / ".studio/image-directories.json").read_text(encoding="utf-8"))["directories"]
        self.assertIn("empty", dirs)
        manifest = json.loads((root / "project.json").read_text(encoding="utf-8"))
        self.assertTrue(manifest["resources"][0]["path"].startswith("assets/images/dir-"))
        import zipfile
        with zipfile.ZipFile(output / "candidate.asproject") as backup:
            self.assertIn(".studio/image-directories.json", backup.namelist())
            self.assertIn(".studio/source-groups.json", backup.namelist())

    def test_missing_member_duplicate_membership_and_duplicate_groups_block(self):
        for content in ("组|missing", "组A|main\r\n组B|main", "组|main|main", "组|main\r\n组"):
            with self.subTest(content=content):
                archive = self.archive({"变量信息.bl": "", "插件/main.cj": "", "插件/插件分组.pz": content})
                converter = Converter(archive)
                output = self.directory / str(len(list(self.directory.iterdir())))
                result = converter.convert(output, "main.cj")
                self.assertEqual("blocked", result["status"])
                self.assertEqual(1, result["blockingCategories"]["organization"])

    def test_disabled_leaf_is_not_reactivated_and_original_fields_preserved(self):
        content = record(_步骤类型="_运行提示", _提示类型="_代码解释", _注释步骤类型="_按键",
                         _触摸类型="_单击", _触摸位置="(1,2,0)", _内容="disabled")
        archive = self.archive({"变量信息.bl": "", "插件/main.cj": content})
        converter = Converter(archive)
        summary = converter.convert(self.directory / "converted", "main.cj")
        self.assertEqual(1, summary["preservedDisabled"])
        self.assertEqual({"task.comment": 1}, summary["mappedKinds"])
        self.assertEqual(0, summary["unsupportedItems"])
        report = json.loads((self.directory / "converted/report.json").read_text(encoding="utf-8"))
        self.assertEqual("(1,2,0)", report["disabledRecords"][0]["record"]["_触摸位置"])

    def test_disabled_container_children_and_malformed_tail_block(self):
        for content in (
                record(_步骤类型="_运行提示", _提示类型="_代码解释", _注释步骤类型="_循环")
                + "\r\n\t" + record(_步骤类型="_按键", _触摸类型="_单击", _触摸位置="(1,2,0)"),
                record(_步骤类型="_循环", _循环类型="_限次循环", _循环时间="2")
                + "\r\n\t" + record(_循环尾="循环尾") + "\r\n\t" + record(_步骤类型="_循环", _循环类型="_空循环", _循环时间="1")):
            archive = self.archive({"变量信息.bl": "", "插件/main.cj": content})
            converter = Converter(archive)
            nodes = converter.convert_flow("插件/main.cj", tree(content))
            self.assertEqual("legacy.unsupported", nodes[0]["kind"])
            self.assertTrue(converter.blockers)

    def test_variable_repeat_string_concat_and_variable_prompt_compile(self):
        variables = "\r\n".join([
            record(_变量名称="count", _变量范围="_当前工程", _变量类型="_整数类型"),
            record(_变量名称="文本", _变量范围="_当前工程", _变量类型="_字符类型"),
        ])
        content = "\r\n".join([
            record(_步骤类型="_语法", _变量操作="_变量赋值", _赋值方式="=", _变量名称="count", _赋值为="2"),
            record(_步骤类型="_语法", _变量操作="_变量赋值", _赋值方式="=", _变量名称="文本", _赋值为='"hello"'),
            record(_步骤类型="_语法", _变量操作="_变量赋值", _赋值方式="=", _变量名称="文本", _赋值为='文本 + " +,世界"'),
            record(_步骤类型="_循环", _循环类型="_限次循环", _循环时间="count"),
            "\t" + record(_步骤类型="_运行提示", _提示类型="_弹出提示", _内容="文本"),
            "\t" + record(_循环尾="循环尾"),
        ])
        archive = self.archive({"变量信息.bl": variables, "插件/main.cj": content})
        converter = Converter(archive)
        converter.declarations()
        nodes = converter.convert_flow("插件/main.cj", tree(content))
        self.assertFalse(converter.blockers)
        self.assertEqual("count", nodes[3]["args"]["timesVariable"])
        self.assertEqual("variable.calculate", nodes[2]["kind"])
        self.assertIn("..", nodes[2]["args"]["expression"])
        self.assertEqual("task.prompt", nodes[4]["kind"])
        checker = pathlib.Path(__file__).resolve().parents[3] / "target/debug/examples/validate_project.exe"
        if checker.is_file():
            summary = Converter(archive).convert(self.directory / "converted", "main.cj", compiler_gate(checker))
            self.assertEqual("converted", summary["status"])

    def test_prompt_string_expression_maps_to_native_output_without_temporary_variables(self):
        variables = record(_变量名称="文本", _变量范围="_当前工程", _变量类型="_字符类型")
        content = record(_步骤类型="_运行提示", _提示类型="_弹出提示", _内容='"进度：" + 文本 + "\\n完成"')
        archive = self.archive({"变量信息.bl": variables, "插件/main.cj": content})
        converter = Converter(archive)
        converter.declarations()
        nodes = converter.convert_flow("插件/main.cj", tree(content))
        self.assertFalse(converter.blockers)
        self.assertEqual(1, len(converter.variables))
        self.assertEqual(1, len(nodes))
        self.assertEqual("task.message", nodes[0]["kind"])
        self.assertEqual("popup", nodes[0]["args"]["channel"])
        self.assertIn("..", nodes[0]["args"]["expression"])
        checker = pathlib.Path(__file__).resolve().parents[3] / "target/debug/examples/validate_project.exe"
        if checker.is_file():
            result = Converter(archive).convert(self.directory / "converted", "main.cj", compiler_gate(checker))
            self.assertEqual("converted", result["status"])

    def test_prompt_expression_does_not_coerce_numbers_drop_title_or_execute_code(self):
        variables = "\r\n".join([
            record(_变量名称="text", _变量范围="_当前工程", _变量类型="_字符类型"),
            record(_变量名称="n", _变量范围="_当前工程", _变量类型="_整数类型"),
        ])
        archive = self.archive({"变量信息.bl": variables, "插件/main.cj": ""})
        for expression, title in [('"value=" + n', ''), ('text + arbitrary()', ''),
                                  ('"hi" + text', 'title'), ('text.Length()', ''),
                                  ('"中"' + ' + "中"' * 200, '')]:
            with self.subTest(expression=expression[:32], title=title):
                converter = Converter(archive)
                converter.declarations()
                content = record(_步骤类型="_运行提示", _提示类型="_弹出提示", _标题=title, _内容=expression)
                self.assertEqual("legacy.unsupported", converter.convert_flow("插件/main.cj", tree(content))[0]["kind"])
                self.assertTrue(converter.blockers)

    def test_prompt_title_and_numeric_concat_are_not_silently_dropped(self):
        variables = "\r\n".join([record(_变量名称="text", _变量范围="_当前工程", _变量类型="_字符类型"),
            record(_变量名称="count", _变量范围="_当前工程", _变量类型="_整数类型")])
        archive = self.archive({"变量信息.bl": variables, "插件/main.cj": ""})
        converter = Converter(archive)
        converter.declarations()
        with self.assertRaises(Unmapped):
            converter.string_expression("插件/main.cj", 'text + count')
        content = record(_步骤类型="_运行提示", _提示类型="_弹出提示", _标题="title", _内容='"hi"')
        self.assertEqual("legacy.unsupported", converter.convert_flow("插件/main.cj", tree(content))[0]["kind"])

    def test_report_category_and_local_node_comparison(self):
        archive = self.archive({"变量信息.bl": "", "插件/main.cj": record(_步骤类型="_语法", _函数名字="unknown")})
        output = self.directory / "converted"
        summary = Converter(archive).convert(output, "main.cj")
        self.assertEqual(1, summary["blockingCategories"]["functions"])
        report = json.loads((output / "report.json").read_text(encoding="utf-8"))
        old, current = inspect_issue(output, report["blockers"][0], report["pluginAliases"])
        self.assertIn("unknown", old)
        self.assertIn("legacy.unsupported", current)
        with self.assertRaises(ValueError):
            inspect_issue(output, report["blockers"][0], {"插件/main.cj": "../../outside"})

    def test_reserved_directory_aliases_and_unicode_group_line_are_preserved(self):
        entries = {"变量信息.bl": "", "插件/main.cj": "", "插件/插件分组.pz": "甲\u2028乙|main\r\n"}
        for name in ("CON", "nul.txt", "COM1", "ending."):
            entries["图片/" + name + "/"] = ""
        archive = self.archive(entries)
        output = self.directory / "converted"
        result = Converter(archive).convert(output, "main.cj")
        self.assertEqual(0, result["unsupportedItems"])
        self.assertEqual(1, result["convertedGroups"])
        report = json.loads((output / "report.json").read_text(encoding="utf-8"))
        self.assertEqual("甲\u2028乙", report["groupAliases"][0]["sourceName"])
        self.assertTrue(all(name.startswith("dir-") for name in report["imageDirectoryAliases"].values()))

    def test_empty_call_markers_map_but_nonempty_or_extra_fields_do_not(self):
        parent = record(_步骤类型="_插件", _路径="/worker.cj")
        markers = "\r\n\t" + record(_显示="运行成功") + "\r\n\t" + record(_显示="运行失败")
        archive = self.archive({"变量信息.bl": "", "插件/main.cj": parent + markers, "插件/worker.cj": ""})
        converter = Converter(archive)
        nodes = converter.convert_flow("插件/main.cj", tree(parent + markers))
        self.assertEqual(["flow.call"], [node["kind"] for node in nodes])
        self.assertEqual(2, len(converter.structural_markers))
        for content in (parent + markers + "\r\n\t\t" + record(_步骤类型="_按键", _触摸类型="_弹起", _触摸位置="(0)"),
                        parent + markers + "_unknown:action┊"):
            with self.subTest(content=content):
                nodes = Converter(archive).convert_flow("插件/main.cj", tree(content))
                self.assertEqual("legacy.unsupported", nodes[0]["kind"])
        checker = pathlib.Path(__file__).resolve().parents[3] / "target/debug/examples/validate_project.exe"
        if checker.is_file():
            result = Converter(archive).convert(self.directory / "converted", "main.cj", compiler_gate(checker))
            self.assertEqual("converted", result["status"])

    def test_loop_tail_with_hidden_action_is_not_removed(self):
        archive = self.archive({"变量信息.bl": "", "插件/main.cj": ""})
        content = record(_步骤类型="_循环", _循环类型="_限次循环", _循环时间="2") + "\r\n\t" + record(
            _循环尾="循环尾", _步骤类型="_按键", _触摸类型="_弹起", _触摸位置="(0)")
        converter = Converter(archive)
        self.assertEqual("legacy.unsupported", converter.convert_flow("插件/main.cj", tree(content))[0]["kind"])
        self.assertFalse(converter.structural_markers)

    def test_reversed_comparison_and_parenthesized_concat_compile(self):
        variables = "\r\n".join([record(_变量名称="count", _变量范围="_当前工程", _变量类型="_整数类型"),
                                record(_变量名称="text", _变量范围="_当前工程", _变量类型="_字符类型")])
        content = "\r\n".join([
            record(_步骤类型="_语法", _变量操作="_变量赋值", _赋值方式="=", _变量名称="text", _赋值为='("a" + text) + ("b")'),
            record(_步骤类型="_语法", _关键字="_IF", _判断式=" 2 < count "),
            "\t" + record(_步骤类型="_运行提示", _提示类型="_弹出提示", _内容='"yes"'),
        ])
        archive = self.archive({"变量信息.bl": variables, "插件/main.cj": content})
        converter = Converter(archive)
        converter.declarations()
        self.assertEqual("greaterThan", converter.comparison("插件/main.cj", "2 < count")["operator"])
        for expression in ('()', 'text ("b")', '("b"', '"b")', 'text + ()', 'text ++ "b"'):
            with self.assertRaises(Unmapped):
                converter.string_expression("插件/main.cj", expression)
        checker = pathlib.Path(__file__).resolve().parents[3] / "target/debug/examples/validate_project.exe"
        if checker.is_file():
            result = Converter(archive).convert(self.directory / "converted", "main.cj", compiler_gate(checker))
            self.assertEqual("converted", result["status"])

    def test_ui_audit_is_private_and_not_misrepresented_as_conversion(self):
        lines = [record(_ID类型="_窗体", _路径="_窗体", _宽="720", _高="1280")]
        lines.extend(record(_ID类型="_按钮", _路径=str(index), _宽="10", _高="30", _字体加粗="1",
                            _左键按下事件="private-handler", _内容="private-text") for index in range(129))
        archive = self.archive({"变量信息.bl": "", "插件/main.cj": "", "界面/synthetic.pz": "\r\n".join(lines)})
        output = self.directory / "converted"
        result = Converter(archive).convert(output, "main.cj")
        self.assertEqual("blocked", result["status"])
        self.assertEqual(1, result["uiAudited"])
        self.assertNotIn("ui-field-limit-256", result["uiContractGaps"])
        self.assertNotIn("private", json.dumps(result))
        self.assertFalse((output / "project.asproject").exists())
        audit = json.loads((output / "ui-audit.json").read_text(encoding="utf-8"))["layouts"][0]
        self.assertEqual(129, audit["controlCount"])
        self.assertIn("extended-styles", audit["contractGaps"])
        report = json.loads((output / "report.json").read_text(encoding="utf-8"))
        old, current = inspect_issue(output, report["blockers"][0], report["pluginAliases"])
        self.assertIn("private-text", old)
        self.assertIn("尚未迁移", current)
        self.assertEqual("ui", report["remainingTasks"][0]["feature"])

    def test_malformed_ui_preserves_original_and_blocks_without_crashing(self):
        archive = self.archive({"变量信息.bl": "", "插件/main.cj": "", "界面/bad.pz": "not:fields┊bad-record"})
        output = self.directory / "converted"
        result = Converter(archive).convert(output, "main.cj")
        self.assertEqual("blocked", result["status"])
        self.assertEqual(0, result["uiAudited"])
        self.assertEqual(1, result["blockingCategories"]["interface"])
        self.assertEqual(archive.raw_bytes, (output / "original.zip").read_bytes())

    def test_cancellable_cycles_explicit_status_and_continue_compile(self):
        variables = record(_变量名称="seconds", _变量范围="_当前工程", _变量类型="_浮点类型")
        for loop_type, duration, expected in (
                ("_无限循环", "", {"yieldMs": 1}),
                ("_限时循环", "0.125", {"yieldMs": 1, "durationMs": 125}),
                ("_限时循环", "seconds", {"yieldMs": 1, "durationVariable": "seconds", "durationUnit": "seconds"})):
            with self.subTest(loop_type=loop_type, duration=duration):
                content = "\r\n".join([
                    record(_步骤类型="_循环", _循环类型=loop_type, _循环时间=duration),
                    "\t" + record(_步骤类型="_跳转", _跳转类型="_结束本次循环"),
                    "\t" + record(_循环尾="循环尾"),
                    record(_步骤类型="_跳转", _跳转类型="_返回成功"),
                ])
                archive = self.archive({"变量信息.bl": variables, "插件/main.cj": content})
                converter = Converter(archive)
                converter.declarations()
                nodes = converter.convert_flow("插件/main.cj", tree(content))
                self.assertFalse(converter.blockers)
                self.assertEqual("control.cycle", nodes[0]["kind"])
                self.assertEqual(expected, nodes[0]["args"])
                self.assertEqual("control.continue", nodes[1]["kind"])
                self.assertEqual({"success": True}, nodes[2]["args"])
                checker = pathlib.Path(__file__).resolve().parents[3] / "target/debug/examples/validate_project.exe"
                if checker.is_file():
                    result = Converter(archive).convert(self.directory / ("cycle-" + str(len(list(self.directory.iterdir())))),
                                                        "main.cj", compiler_gate(checker))
                    self.assertEqual("converted", result["status"])

    def test_cycle_duration_bounds_and_status_false_are_not_coerced(self):
        archive = self.archive({"变量信息.bl": "", "插件/main.cj": ""})
        for loop_type, duration in (("_限时循环", "0"), ("_限时循环", "0.0001"),
                                   ("_限时循环", "86400.001"), ("_无限循环", "1")):
            converter = Converter(archive)
            content = record(_步骤类型="_循环", _循环类型=loop_type, _循环时间=duration)
            self.assertEqual("legacy.unsupported", converter.convert_flow("插件/main.cj", tree(content))[0]["kind"])
        converter = Converter(archive)
        content = record(_步骤类型="_跳转", _跳转类型="_返回失败")
        node = converter.convert_flow("插件/main.cj", tree(content))[0]
        self.assertEqual("flow.status.return", node["kind"])
        self.assertEqual({"success": False}, node["args"])

    def test_remaining_tasks_distinguish_byte_strings_from_other_expressions(self):
        from legacy_converter.requirements import remaining_tasks
        issues = [
            {"category": "variables", "reason": "calculation operator/function needs equivalent translation",
             "record": {"_赋值为": "text.Length()"}},
            {"category": "loops", "reason": "unsupported", "record": {"_循环类型": "_无限循环"}},
            {"category": "prompts", "reason": "unsupported", "record": {"_提示类型": "_文字记录"}},
        ]
        tasks = remaining_tasks(issues)
        self.assertEqual({"text-bytes", "infinite", "file-log"}, {task["feature"] for task in tasks})
        self.assertEqual("text-bytes", issues[0]["requiredFeature"])

    def test_gui_filter_scans_beyond_first_thousand_without_logging_content(self):
        import tkinter as tk
        from legacy_converter.gui import ConverterWindow
        from legacy_converter.diagnostics import CATEGORY_LABELS
        root = tk.Tk()
        root.withdraw()
        try:
            window = ConverterWindow(root)
            window.issue_records = [{"source": "private-source", "line": index, "category": "loops",
                                     "reason": "unsupported"} for index in range(1001)]
            window.issue_records.append({"source": "private-last", "line": 1002, "category": "vision", "reason": "unsupported"})
            window.issue_category.set(CATEGORY_LABELS["vision"])
            window.refresh_issues()
            self.assertEqual(("1001",), window.issues.get_children())
            self.assertNotIn("private", window.log_view.get("1.0", "end"))
            window.issue_category.set("全部")
            window.refresh_issues()
            self.assertEqual(1000, len(window.issues.get_children()))
        finally:
            root.destroy()

    def test_legacy_length_uses_native_byte_length_but_other_methods_stay_blocked(self):
        variables = "\r\n".join([
            record(_变量名称="text", _变量范围="_当前工程", _变量类型="_字符类型"),
            record(_变量名称="count", _变量范围="_当前工程", _变量类型="_整数类型"),
        ])
        content = "\r\n".join([
            record(_步骤类型="_语法", _变量操作="_变量赋值", _赋值方式="=", _变量名称="text", _赋值为='"中文"'),
            record(_步骤类型="_语法", _变量操作="_变量赋值", _赋值方式="=", _变量名称="count", _赋值为="text.Length() + 1"),
        ])
        archive = self.archive({"变量信息.bl": variables, "插件/main.cj": content})
        converter = Converter(archive)
        converter.declarations()
        rewritten = converter.arithmetic("插件/main.cj", "text.字符长度() + 1", converter.variable("插件/main.cj", "count"))
        self.assertEqual("byteLen(text) + 1", rewritten)
        for source in ('text.Mid(1,1)', 'text.Find("中",0)', 'text.Length(1)', 'count.Length()',
                       'byteLen(count)', 'text.Length() + arbitrary(1)', 'text.Length() + byteLen(text.Length())'):
            with self.assertRaises(Unmapped):
                converter.arithmetic("插件/main.cj", source, converter.variable("插件/main.cj", "count"))
        checker = pathlib.Path(__file__).resolve().parents[3] / "target/debug/examples/validate_project.exe"
        if checker.is_file():
            summary = Converter(archive).convert(self.directory / "native-length", "main.cj", compiler_gate(checker))
            self.assertEqual("converted", summary["status"])
