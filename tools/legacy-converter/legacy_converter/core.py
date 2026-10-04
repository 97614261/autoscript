"""Standalone legacy archive conversion; never execute imported scripts or libraries."""
from __future__ import annotations

import ast
import collections
import json
import pathlib
import re
import stat
import zipfile
import io
import hashlib
import math
import struct
import tempfile
import zlib
import time
from dataclasses import dataclass, field

MAX_ARCHIVE_BYTES = 64 * 1024 * 1024
MAX_TOTAL_BYTES = 128 * 1024 * 1024
MAX_ENTRY_BYTES = 16 * 1024 * 1024


class Cancelled(Exception):
    """Conversion was cancelled before publishing output."""


class Job:
    def __init__(self, cancel=None, progress=None, timeout=180):
        self.cancel = cancel or (lambda: False)
        self.progress = progress or (lambda stage, index, total: None)
        self.deadline = time.monotonic() + timeout

    def check(self, stage=None, index=0, total=0):
        if self.cancel():
            raise Cancelled("conversion cancelled")
        if time.monotonic() >= self.deadline:
            raise TimeoutError("conversion deadline exceeded")
        if stage:
            self.progress(stage, index, total)


def record_lines(text: str) -> list[str]:
    # Android's original createTree uses CRLF, not Java/Python Unicode line breaks.
    delimiter = "\r\n" if "\r\n" in text else "\n"
    if text.count(delimiter) >= 100_000:
        raise ValueError("legacy record count exceeds budget")
    return text.split(delimiter)


def fields(line: str) -> dict[str, str]:
    if len(line) > 65_536:
        raise ValueError("legacy record exceeds budget")
    result = {}
    record = line.lstrip("\t")
    if record.endswith("┊@"):
        record = record[:-1]
    for item in record.split("┊"):
        if not item:
            continue
        key, separator, value = item.partition(":")
        if not separator or not key:
            raise ValueError("invalid legacy key/value record")
        if key in result:
            raise ValueError("duplicate legacy field")
        result[key] = value
    return result


class LegacyArchive:
    def __init__(self, path: pathlib.Path):
        if not path.is_file() or path.stat().st_size > MAX_ARCHIVE_BYTES:
            raise ValueError("archive missing or exceeds 64 MiB")
        with path.open("rb") as source:
            self.raw_bytes = source.read(MAX_ARCHIVE_BYTES + 1)
        if len(self.raw_bytes) > MAX_ARCHIVE_BYTES:
            raise ValueError("archive exceeds 64 MiB")
        # One immutable snapshot: user changes after opening cannot mismatch converted data/backup.
        self.archive = zipfile.ZipFile(io.BytesIO(self.raw_bytes))
        self.entries: dict[str, zipfile.ZipInfo] = {}
        self.directories = set()
        total = 0
        try:
            if len(self.archive.infolist()) > 2048:
                raise ValueError("archive has too many entries")
            roots = set()
            for item in self.archive.infolist():
                # Python on Windows normalizes backslashes and truncates NUL in ZipInfo.filename.
                # Validate orig_filename too, before trusting the normalized inventory.
                if item.orig_filename != item.filename:
                    raise ValueError("unsafe or normalized archive path")
                path_parts = item.filename.rstrip("/").split("/")
                if (not all(part and part not in (".", "..") for part in path_parts)
                        or "\\" in item.filename or ":" in item.filename
                        or any(ord(character) < 32 for character in item.filename)
                        or stat.S_ISLNK(item.external_attr >> 16)):
                    raise ValueError("unsafe archive path")
                roots.add(path_parts[0])
                total += item.file_size
                if item.file_size > MAX_ENTRY_BYTES or total > MAX_TOTAL_BYTES:
                    raise ValueError("expanded archive exceeds size budget")
            if len(roots) != 1:
                raise ValueError("expected one project directory")
            self.name = next(iter(roots))
            prefix = self.name + "/"
            for item in self.archive.infolist():
                if item.is_dir():
                    relative = item.filename[len(prefix):].rstrip("/")
                    if relative:
                        self.directories.add(relative)
                    continue
                if not item.filename.startswith(prefix):
                    raise ValueError("archive entry outside project directory")
                relative = item.filename[len(prefix):]
                if relative in self.entries:
                    raise ValueError("duplicate archive entry")
                self.entries[relative] = item
            if "变量信息.bl" not in self.entries:
                raise ValueError("legacy project variable file is missing")
        except Exception:
            self.archive.close()
            raise

    def read(self, name: str) -> bytes:
        with self.archive.open(self.entries[name]) as stream:
            data = stream.read(MAX_ENTRY_BYTES + 1)
        if len(data) > MAX_ENTRY_BYTES:
            raise ValueError("expanded entry exceeds budget")
        return data

    def text(self, name: str) -> str:
        return self.read(name).decode("utf-8-sig", errors="strict")

    def close(self) -> None:
        self.archive.close()

    def inspect(self, job=None) -> dict:
        job = job or Job()
        steps = collections.Counter()
        levels = collections.Counter()
        known_steps = {"_循环", "_语法", "_按键", "_跳转", "_插件", "_运行提示", "_寻图"}
        for name in self.entries:
            job.check()
            if not name.endswith(".cj"):
                continue
            for line in record_lines(self.text(name)):
                job.check()
                if not line:
                    continue
                values = fields(line)
                kind = values.get("_步骤类型")
                kind = kind if kind in known_steps else "<other>"
                steps[kind] += 1
                levels[len(line) - len(line.lstrip("\t"))] += 1
        variables = [fields(line) for line in record_lines(self.text("变量信息.bl")) if line]
        known_types = {"_整数类型", "_浮点类型", "_字符类型", "_寻图类型"}
        return {
            "plugins": sum(name.endswith(".cj") for name in self.entries),
            "images": sum(name.endswith(".bmp") for name in self.entries),
            "steps": steps,
            "depths": levels,
            "variables": len(variables),
            "variableTypes": collections.Counter(value.get("_变量类型") if value.get("_变量类型") in known_types
                                                  else "<other>" for value in variables),
            "variableScopes": collections.Counter("global" if value.get("_变量范围") == "_当前工程"
                                                   else "local" if value.get("_变量范围", "").endswith(".cj")
                                                   else "<other>" for value in variables),
            "libraries": sum(name.endswith(".plugin") for name in self.entries),
            "uiLayouts": sum(name.startswith("界面/") and name.endswith(".pz") for name in self.entries),
        }


@dataclass
class LegacyNode:
    line: int
    values: dict[str, str]
    children: list[LegacyNode] = field(default_factory=list)


def tree(text: str) -> list[LegacyNode]:
    """Do not flatten success/failure branches or silently repair broken indentation."""
    roots: list[LegacyNode] = []
    stack: list[LegacyNode] = []
    for number, line in enumerate(record_lines(text), 1):
        if not line:
            continue
        depth = len(line) - len(line.lstrip("\t"))
        if depth > 32 or depth > len(stack):
            raise ValueError("invalid legacy tree indentation")
        node = LegacyNode(number, fields(line))
        if not node.values:
            raise ValueError("empty legacy record")
        del stack[depth:]
        (stack[-1].children if stack else roots).append(node)
        stack.append(node)
    return roots


def stable_id(prefix: str, value: str) -> str:
    return prefix + hashlib.sha256(value.encode("utf-8")).hexdigest()[:24]


def identifier(value: str, prefix: str = "v_") -> str:
    return value if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,63}", value) else stable_id(prefix, value)


def bmp_png(data: bytes) -> bytes:
    """BI_RGB 24/32-bit BMP to RGB PNG. BI_RGB's unused fourth byte is NOT alpha."""
    if len(data) < 54 or data[:2] != b"BM":
        raise ValueError("invalid BMP header")
    size, offset = struct.unpack_from("<IxxxxI", data, 2)
    header = struct.unpack_from("<I", data, 14)[0]
    width, height, planes, bits, compression = struct.unpack_from("<iiHHI", data, 18)
    if (header != 40 or planes != 1 or compression != 0 or bits not in (24, 32)
            or not 1 <= width <= 4096 or not 1 <= abs(height) <= 8192
            or width * abs(height) > 8_000_000 or size != len(data) or offset < 54):
        raise ValueError("unsupported or invalid BMP layout")
    stride = ((width * bits + 31) // 32) * 4
    if offset + stride * abs(height) > len(data):
        raise ValueError("truncated BMP pixels")
    rows = bytearray()
    for y in range(abs(height)):
        source_y = abs(height) - 1 - y if height > 0 else y
        start = offset + source_y * stride
        rows.append(0)  # PNG filter None.
        for x in range(width):
            position = start + x * (bits // 8)
            rows.extend((data[position + 2], data[position + 1], data[position]))

    def chunk(kind: bytes, content: bytes) -> bytes:
        return (struct.pack(">I", len(content)) + kind + content
                + struct.pack(">I", zlib.crc32(kind + content)))

    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", width, abs(height), 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(bytes(rows))) + chunk(b"IEND", b""))


class Unmapped(ValueError):
    """A semantic gap, not a malformed archive or a reason to execute raw code."""


class Converter:
    def __init__(self, archive: LegacyArchive, job=None):
        self.archive = archive
        self.job = job or Job()
        names = sorted(name for name in archive.entries if name.endswith(".cj"))
        if not 1 <= len(names) <= 256:
            raise ValueError("legacy plugin count outside 1..256")
        self.flows = {name: stable_id("flow-", name) for name in names}
        self.variables: dict[tuple[str, str], dict] = {}
        self.aliases: list[dict] = []
        self.blockers: list[dict] = []
        self.mapped = collections.Counter()
        self.source_records = 0
        self.emitted = 0
        self.images: dict[str, str] = {}
        self.disabled = []
        self.ui_audits = []
        self.structural_markers = []

    def comparison(self, plugin: str, expression: str) -> dict:
        if len(expression) > 1024:
            raise Unmapped("condition exceeds bounds")
        match = re.fullmatch(r"\s*(.+?)\s*(==|!=|<=|>=|<|>)\s*(.+?)\s*", expression)
        if not match:
            raise Unmapped("condition requires a simple typed comparison")
        left, operator, right = match.groups()
        left, right = left.strip(), right.strip()
        # A literal on the left can be swapped without changing a same-typed comparison.
        if ((plugin, left) not in self.variables and ("", left) not in self.variables
                and ((plugin, right) in self.variables or ("", right) in self.variables)):
            left, right = right, left
            operator = {"<": ">", "<=": ">=", ">": "<", ">=": "<=", "==": "==", "!=": "!="}[operator]
        declaration = self.variable(plugin, left.strip())
        args = {"variable": declaration["name"], "operator": {
            "==": "equals", "!=": "notEquals", "<": "lessThan", "<=": "lessOrEqual",
            ">": "greaterThan", ">=": "greaterOrEqual"}[operator]}
        source = self.variables.get((plugin, right)) or self.variables.get(("", right))
        if source:
            if source["type"] != declaration["type"]:
                raise Unmapped("comparison variable types differ")
            args.update(value=0 if declaration["type"] != "string" else "", valueVariable=source["name"])
            return args
        try:
            literal = json.loads(right)
        except ValueError:
            raise Unmapped("comparison operand needs expression translation") from None
        valid = ((declaration["type"] == "integer" and type(literal) is int and -(2**63) <= literal < 2**63)
                 or (declaration["type"] == "number" and type(literal) in (int, float)
                     and abs(literal) <= 1.7976931348623157e308 and math.isfinite(literal))
                 or (declaration["type"] == "string" and isinstance(literal, str) and len(literal) <= 2048))
        if not valid:
            raise Unmapped("comparison literal type or bounds differ")
        args["value"] = literal
        return args

    def arithmetic(self, plugin: str, expression: str, target: dict) -> str:
        if target["type"] not in ("integer", "number") or not 1 <= len(expression) <= 1024:
            raise Unmapped("calculation requires a bounded numeric expression")
        pattern = re.compile(r"\s*(?:([0-9]+(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)|((?:[^\W\d]|_)\w*)|([()+*\-]))")
        length_pattern = re.compile(r"\s*((?:[^\W\d]|_)\w*)\.(?:Length|字符长度)\(\s*\)")
        length_sources = set()
        tokens = []
        position = 0
        while position < len(expression.rstrip()):
            length_call = length_pattern.match(expression, position)
            if length_call:
                declaration = self.variable(plugin, length_call.group(1))
                if declaration["type"] != "string":
                    raise Unmapped("calculation string length source must be string")
                length_sources.add(declaration["name"])
                tokens.append("byteLen(" + declaration["name"] + ")")
                position = length_call.end()
                if len(tokens) > 128:
                    raise Unmapped("calculation token budget exceeded")
                continue
            match = pattern.match(expression, position)
            if not match:
                raise Unmapped("calculation operator/function needs equivalent translation")
            number, name, operator = match.groups()
            if name:
                declaration = self.variable(plugin, name)
                if declaration["type"] not in ("integer", "number") or (
                        target["type"] == "integer" and declaration["type"] != "integer"):
                    raise Unmapped("calculation variable type differs")
                tokens.append(declaration["name"])
            elif number:
                value = float(number) if any(c in number for c in ".eE") else int(number)
                if (abs(value) > 2**53 or not math.isfinite(value)
                        or target["type"] == "integer" and type(value) is not int):
                    raise Unmapped("calculation literal outside exact numeric bounds")
                tokens.append(number)
            else:
                tokens.append(operator)
            position = match.end()
            if len(tokens) > 128:
                raise Unmapped("calculation token budget exceeded")
        rewritten = " ".join(tokens)
        if len(rewritten.encode("utf-8")) > 1024:
            raise Unmapped("rewritten calculation exceeds bounds")
        try:
            parsed = ast.parse(rewritten, mode="eval")
        except (SyntaxError, RecursionError):
            raise Unmapped("calculation syntax invalid") from None
        if any(not isinstance(node, (ast.Expression, ast.BinOp, ast.UnaryOp, ast.Add, ast.Sub,
                                     ast.Mult, ast.UAdd, ast.USub, ast.Name, ast.Load, ast.Constant, ast.Call))
               for node in ast.walk(parsed)) or any(operator in expression for operator in ("**", "++", "--")):
            raise Unmapped("calculation syntax/operator not supported")
        for node in ast.walk(parsed):
            if isinstance(node, ast.Call) and not (
                    isinstance(node.func, ast.Name) and node.func.id == "byteLen"
                    and len(node.args) == 1 and not node.keywords and isinstance(node.args[0], ast.Name)
                    and node.args[0].id in length_sources):
                raise Unmapped("calculation operator/function needs equivalent translation")
        return rewritten

    def block(self, source: str, line: int, reason: str, record: dict | None = None) -> None:
        from .diagnostics import category
        self.blockers.append({"source": source, "line": line, "reason": reason, "record": record,
                              "category": category(reason, record)})

    def string_expression(self, plugin: str, expression: str) -> str:
        """Only typed string '+' concatenation; never interpret arbitrary C++/Lua."""
        if not 1 <= len(expression) <= 1024:
            raise Unmapped("string expression exceeds bounds")
        pattern = re.compile(r'\s*(?:((?:[^\W\d]|_)\w*)|("(?:[^"\\]|\\.)*")|([()+]))')
        tokens, position, expect_value, depth = [], 0, True, 0
        while position < len(expression.rstrip()):
            match = pattern.match(expression, position)
            if not match:
                raise Unmapped("string expression requires typed literal/variable concatenation")
            name, quoted, plus = match.groups()
            if plus == "(":
                if not expect_value or depth >= 16:
                    raise Unmapped("string expression syntax invalid")
                tokens.append("(")
                depth += 1
            elif plus == ")":
                if expect_value or not depth:
                    raise Unmapped("string expression syntax invalid")
                tokens.append(")")
                depth -= 1
            elif plus:
                if expect_value:
                    raise Unmapped("string expression syntax invalid")
                tokens.append("..")
                expect_value = True
            else:
                if not expect_value:
                    raise Unmapped("string expression syntax invalid")
                if name:
                    declaration = self.variable(plugin, name)
                    if declaration["type"] != "string":
                        raise Unmapped("string concatenation requires explicit numeric conversion")
                    tokens.append(declaration["name"])
                else:
                    try:
                        value = json.loads(quoted)
                    except ValueError:
                        raise Unmapped("string literal escape syntax unsupported") from None
                    if len(value) > 2048:
                        raise Unmapped("string literal exceeds bounds")
                    tokens.append(json.dumps(value, ensure_ascii=False))
                expect_value = False
            position = match.end()
            if len(tokens) > 128:
                raise Unmapped("string expression token budget exceeded")
        if expect_value or not tokens or depth:
            raise Unmapped("string expression syntax invalid")
        rewritten = " ".join(tokens)
        if len(rewritten) > 1024:
            raise Unmapped("rewritten string expression exceeds bounds")
        return rewritten

    def plugin_path(self, value: str) -> str:
        # Legacy configPlugin stores '/name.cj'; local variable scopes store 'name.cj'.
        normalized = value.lstrip("/")
        candidate = normalized if normalized.startswith("插件/") else "插件/" + normalized
        if candidate not in self.flows:
            raise Unmapped("referenced plugin is missing")
        return candidate

    def declarations(self) -> list[dict]:
        result = []
        all_names = set()
        for number, line in enumerate(record_lines(self.archive.text("变量信息.bl")), 1):
            self.job.check()
            if not line:
                continue
            value = fields(line)
            original = value.get("_变量名称", "")
            old_scope = value.get("_变量范围", "")
            old_type = value.get("_变量类型", "")
            if not original or (old_scope, original) in all_names:
                raise ValueError("missing or duplicate legacy variable")
            all_names.add((old_scope, original))
            if len(all_names) > 512:
                raise ValueError("legacy variable count exceeds 512")
            value_type = {"_整数类型": "integer", "_浮点类型": "number", "_字符类型": "string"}.get(old_type)
            if value_type is None:
                self.block("变量信息.bl", number, "legacy result-object/variable type is not equivalent to a screen frame", value)
                continue
            try:
                scope = "" if old_scope == "_当前工程" else self.plugin_path(old_scope)
            except Unmapped as error:
                self.block("变量信息.bl", number, str(error), value)
                continue
            name = identifier(original)
            declaration = {"name": name, "scope": "flow" if scope else "global",
                           "flowId": self.flows[scope] if scope else None, "type": value_type}
            # Hash aliases could collide with an explicitly named ASCII variable; fail, never overwrite.
            if any(item["name"] == name and item["flowId"] == declaration["flowId"] for item in result):
                raise ValueError("variable alias collision")
            self.variables[(scope, original)] = declaration
            self.aliases.append({"sourceName": original, "sourceScope": old_scope, **declaration})
            result.append(declaration)
        if len(result) > 512:
            raise ValueError("converted variable count exceeds 512")
        return result

    def variable(self, plugin: str, value: str) -> dict:
        declaration = self.variables.get((plugin, value)) or self.variables.get(("", value))
        if declaration is None:
            raise Unmapped("variable is undeclared or has an unsupported type")
        return declaration

    def operation(self, plugin: str, node: LegacyNode) -> tuple[str, dict, list[LegacyNode]]:
        values = node.values
        kind = values.get("_步骤类型")
        if values.get("_注释步骤类型"):
            if (kind == "_运行提示" and values.get("_提示类型") == "_代码解释"
                    and not node.children):
                self.disabled.append({"source": plugin, "line": node.line, "record": values})
                return "task.comment", {"message": "已禁用的旧步骤；原字段保留于转换报告"}, []
            raise Unmapped("disabled container semantics require confirmation")
        if kind == "_循环" and values.get("_循环类型") in ("_限次循环", "_限时循环", "_无限循环"):
            raw = values.get("_循环时间", "")
            repeat = values["_循环类型"] == "_限次循环"
            args = {"times": 0} if repeat else {"yieldMs": 1}
            if repeat:
                if re.fullmatch(r"[0-9]+", raw):
                    if not 0 <= int(raw) <= 1_000_000:
                        raise Unmapped("repeat literal exceeds current bound")
                    args["times"] = int(raw)
                else:
                    declaration = self.variable(plugin, raw)
                    if declaration["type"] != "integer":
                        raise Unmapped("repeat count variable must be integer")
                    args["timesVariable"] = declaration["name"]
            elif values["_循环类型"] == "_限时循环":
                if re.fullmatch(r"[0-9]+(?:\.[0-9]+)?", raw):
                    from decimal import Decimal
                    milliseconds = Decimal(raw) * 1000
                    if not 1 <= milliseconds <= 86_400_000 or milliseconds != int(milliseconds):
                        raise Unmapped("timed loop seconds outside exact millisecond bounds")
                    args["durationMs"] = int(milliseconds)
                else:
                    declaration = self.variable(plugin, raw)
                    if declaration["type"] not in ("integer", "number"):
                        raise Unmapped("timed loop duration variable must be numeric")
                    args.update(durationVariable=declaration["name"], durationUnit="seconds")
            elif raw:
                raise Unmapped("infinite loop contains an unexpected duration")
            children = []
            for child in node.children:
                if (child.values.get("_循环尾") == "循环尾" and not child.children
                        and child == node.children[-1] and set(child.values) <= {"_显示", "_循环尾"}
                        and child.values.get("_显示", "循环尾") == "循环尾"):
                    self.structural_markers.append({"source": plugin, "line": child.line, "record": child.values})
                    continue
                if child.values.get("_循环尾"):
                    raise Unmapped("loop tail marker is misplaced or has executable children")
                children.append(child)
            return "control.repeat" if repeat else "control.cycle", args, children
        empty_call_branches = (kind == "_插件" and len(node.children) == 2
            and [child.values for child in node.children] == [{"_显示": "运行成功"}, {"_显示": "运行失败"}]
            and not any(child.children for child in node.children))
        if node.children and not empty_call_branches:
            raise Unmapped("container/conditional branches need an equivalent control-flow mapping")
        if kind == "_循环" and values.get("_循环类型") == "_空循环":
            raw = values.get("_循环时间", "")
            if re.fullmatch(r"[0-9]+", raw) and 0 <= int(raw) <= 86_400_000:
                return "task.sleep", {"milliseconds": int(raw)}, []
            variable = self.variable(plugin, raw)
            if variable["type"] != "integer":
                raise Unmapped("sleep duration variable must be integer")
            return "task.sleep", {"milliseconds": 0, "millisecondsVariable": variable["name"]}, []
        if kind == "_运行提示":
            tip = values.get("_提示类型")
            # Disabled steps are encoded as comments with original fields; do not reactivate them.
            if values.get("_注释步骤类型"):
                raise Unmapped("disabled legacy step retained without reactivation")
            message = values.get("_内容", "")
            if not message or len(message) > 2048:
                raise Unmapped("prompt/comment content outside current bounds")
            if tip == "_代码解释":
                return "task.comment", {"message": message}, []
            if tip == "_弹出提示":
                if message in {name for scope, name in self.variables if scope in ("", plugin)}:
                    declaration = self.variable(plugin, message)
                    if values.get("_标题"):
                        raise Unmapped("legacy prompt title is not equivalent to the current prompt")
                    return "task.prompt", {"message": "变量提示", "valueVariable": declaration["name"]}, []
                # Legacy content is an expression: only literals or typed string
                # concatenation pass; never execute the original expression.
                try:
                    literal = json.loads(message)
                except ValueError:
                    if values.get("_标题"):
                        raise Unmapped("legacy prompt title is not equivalent to the current prompt") from None
                    try:
                        expression = self.string_expression(plugin, message)
                    except Unmapped:
                        raise Unmapped("prompt content is an expression, not a quoted literal") from None
                    if len(expression.encode("utf-8")) > 1024:
                        raise Unmapped("rewritten string expression exceeds bounds")
                    return "task.message", {"expression": expression, "channel": "popup"}, []
                if isinstance(literal, str) and 1 <= len(literal) <= 2048:
                    if values.get("_标题"):
                        raise Unmapped("legacy prompt title is not equivalent to the current prompt")
                    return "task.prompt", {"message": literal}, []
        if kind == "_按键":
            touch = values.get("_触摸类型")
            position = values.get("_触摸位置", "")
            if touch == "_弹起" and position == "(0)":
                return "input.pointerup", {}, []
            triple = re.fullmatch(r"\(([0-9]+),([0-9]+),0\)", position)
            if touch in ("_单击", "_按下") and triple:
                x, y = map(int, triple.groups())
                if x > 2_147_483_647 or y > 2_147_483_647:
                    raise Unmapped("input coordinate outside current bounds")
                return ("input.tap" if touch == "_单击" else "input.pointerdown"), {"x": x, "y": y}, []
            drag = re.fullmatch(r"\(([0-9]+),([0-9]+),0\)\(([0-9]+),([0-9]+),([0-9]+)\)", position)
            if touch == "_拖动" and drag:
                x1, y1, x2, y2, duration = map(int, drag.groups())
                if max(x1, y1, x2, y2) <= 2_147_483_647 and 1 <= duration <= 5000:
                    return "input.swipe", {"x1": x1, "y1": y1, "x2": x2, "y2": y2, "durationMs": duration}, []
            raise Unmapped("nonzero finger, expression, or unsupported drag duration")
        if kind == "_跳转":
            jump = values.get("_跳转类型")
            direct = {"_跳出循环": "control.break", "_结束本次循环": "control.continue"}
            if jump in direct:
                return direct[jump], {}, []
            if jump in ("_返回成功", "_返回失败"):
                return "flow.status.return", {"success": jump == "_返回成功"}, []
            if jump in ("_增加标号", "_跳转到标号"):
                label = values.get("_标号名称", "")
                if not label:
                    raise Unmapped("label is missing")
                return ("control.label" if jump == "_增加标号" else "control.goto"), {"name": identifier(label, "label_")}, []
            raise Unmapped("legacy success/failure return status is not a void return")
        if (kind == "_语法" and values.get("_变量操作") == "_变量赋值"
                and values.get("_赋值方式") == "="):
            target = self.variable(plugin, values.get("_变量名称", ""))
            raw = values.get("_赋值为", "")
            if len(raw) > 2048:
                raise Unmapped("assignment literal exceeds bounds")
            try:
                literal = json.loads(raw)
            except ValueError:
                try:
                    source = self.variable(plugin, raw)
                except Unmapped:
                    if target["type"] == "string":
                        rewritten = self.string_expression(plugin, raw)
                        return "variable.calculate", {"name": target["name"], "expression": rewritten}, []
                    rewritten = self.arithmetic(plugin, raw, target)
                    return "variable.calculate", {"name": target["name"], "expression": rewritten}, []
                if source["type"] != target["type"]:
                    raise Unmapped("implicit legacy assignment coercion needs translation")
                return "variable.copy", {"name": target["name"], "sourceName": source["name"]}, []
            valid = ((target["type"] == "string" and isinstance(literal, str) and len(literal) <= 2048)
                     or (target["type"] == "integer" and type(literal) is int and -(2**63) <= literal < 2**63)
                     or (target["type"] == "number" and type(literal) in (int, float)
                         and abs(literal) <= 1.7976931348623157e308 and math.isfinite(literal)))
            if not valid:
                raise Unmapped("assignment literal does not match declaration type or bounds")
            return "variable.set", {"name": target["name"], "value": literal}, []
        if kind == "_插件":
            target = self.plugin_path(values.get("_路径", ""))
            if empty_call_branches:
                self.structural_markers.extend({"source": plugin, "line": child.line, "record": child.values}
                                               for child in node.children)
            return "flow.call", {"targetFlowId": self.flows[target], "arguments": {}}, []
        raise Unmapped("legacy instruction has no confirmed equivalent yet")

    def convert_flow(self, plugin: str, nodes: list[LegacyNode]) -> list[dict]:
        result: list[dict] = []
        root = "root-" + self.flows[plugin]

        def visit(items: list[LegacyNode], block: str, parent: str | None, depth: int) -> None:
            skip = set()
            for index, node in enumerate(items):
                self.job.check()
                if index in skip:
                    continue
                self.source_records += 1
                node_id = stable_id("node-", plugin + ":" + str(node.line))
                condition_failure = None
                if node.values.get("_步骤类型") == "_语法" and node.values.get("_关键字") == "_IF":
                    chain = [node]
                    cursor = index + 1
                    while cursor < len(items) and items[cursor].values.get("_关键字") == "_EIF" and items[cursor].values.get("_步骤类型") == "_语法":
                        chain.append(items[cursor])
                        cursor += 1
                    otherwise = None
                    if cursor < len(items) and items[cursor].values.get("_关键字") == "_ELSE" and items[cursor].values.get("_步骤类型") == "_语法":
                        otherwise = items[cursor]
                        cursor += 1
                    try:
                        if len(chain) > 33:
                            raise Unmapped("condition branch budget exceeded")
                        comparisons = [self.comparison(plugin, branch.values.get("_判断式", "")) for branch in chain]
                    except Unmapped as error:
                        # Whole chain remains raw/blocked; preserve the actual failed predicate reason.
                        condition_failure = str(error)
                    else:
                        args = comparisons[0]
                        if len(comparisons) > 1:
                            args["elseIf"] = comparisons[1:]
                        children = {"then": "then-" + node_id, "else": "else-" + node_id}
                        for branch_index in range(len(chain) - 1):
                            children[f"elseIf{branch_index}"] = f"elif{branch_index}-" + node_id
                        result.append({"flowSchemaVersion": 1, "nodeId": node_id, "blockId": block,
                            "parentId": parent, "orderKey": f"a{index:06d}", "kind": "control.if",
                            "nodeVersion": 1, "depth": depth, "childBlocks": children, "args": args})
                        self.mapped["control.if"] += 1
                        skip.update(range(index + 1, cursor))
                        for branch_index, branch in enumerate(chain):
                            key = "then" if branch_index == 0 else f"elseIf{branch_index - 1}"
                            visit(branch.children, children[key], node_id, depth + 1)
                        if otherwise:
                            visit(otherwise.children, children["else"], node_id, depth + 1)
                        continue
                try:
                    if condition_failure:
                        raise Unmapped(condition_failure)
                    kind, args, children = self.operation(plugin, node)
                    self.mapped[kind] += 1
                except Unmapped as error:
                    kind, args, children = "legacy.unsupported", {"source": plugin, "line": node.line,
                                                               "reason": str(error), "legacy": node.values}, node.children
                    self.block(plugin, node.line, str(error), node.values)
                envelope = {"flowSchemaVersion": 1, "nodeId": node_id, "blockId": block,
                            "parentId": parent, "orderKey": f"a{index:06d}", "kind": kind,
                            "nodeVersion": 1, "depth": depth, "args": args}
                if kind in ("control.repeat", "control.cycle") or children:
                    child_block = "body-" + node_id
                    envelope["childBlocks"] = {"body": child_block}
                result.append(envelope)
                if children:
                    visit(children, child_block, node_id, depth + 1)

        visit(nodes, root, None, 0)
        self.emitted += len(result)
        return result

    def convert(self, output: pathlib.Path, entry: str | None = None, validator=None) -> dict:
        self.job.check("prepare", 0, 1)
        output = output.absolute()
        if output.exists() or output.is_symlink() or output.name in ("", ".", ".."):
            raise ValueError("output directory already exists or is invalid")
        output.parent.mkdir(parents=True, exist_ok=True)
        temporary = pathlib.Path(tempfile.mkdtemp(prefix=".converting-", dir=output.parent))
        project = temporary / "project-draft"
        project.mkdir()
        (project / "visual" / "flows").mkdir(parents=True)
        (project / "assets" / "images").mkdir(parents=True)
        from .organization import groups, image_directories
        group_aliases = groups(self, project)
        directory_aliases = image_directories(self, project)
        declarations = self.declarations()
        if entry is None:
            selected = next(iter(self.flows))
            self.block("project", 0, "entry plugin must be explicitly confirmed; provisional first plugin is not an entry inference")
        else:
            selected = self.plugin_path(entry)
        sources = {}
        record_budget = 0
        for flow_index, name in enumerate(self.flows):
            self.job.check("plugins", flow_index + 1, len(self.flows))
            text = self.archive.text(name)
            lines = record_lines(text)
            record_budget += sum(bool(line) for line in lines)
            if record_budget > 100_000:
                raise ValueError("project record count exceeds conversion budget")
            sources[name] = self.convert_flow(name, tree(text))
        for resource_index, name in enumerate(self.archive.entries):
            self.job.check("resources", resource_index + 1, len(self.archive.entries))
            if name.lower().endswith(".bmp"):
                source_directory = name[3:].rsplit("/", 1)[0] if name.startswith("图片/") and name.count("/") > 1 else ""
                folder = directory_aliases.get(source_directory, "")
                path = "assets/images/" + (folder + "/" if folder else "") + stable_id("image-", name) + ".png"
                try:
                    png = bmp_png(self.archive.read(name))
                except ValueError as error:
                    self.block(name, 0, str(error))
                    continue
                (project / path).write_bytes(png)
                self.images[name] = path
            elif name.endswith(".plugin"):
                self.block(name, 0, "external APK/DEX library preserved, never executed or translated to arbitrary host commands")
            elif name.startswith("界面/") and name.endswith(".pz"):
                from .requirements import audit_ui
                try:
                    audit = audit_ui(self.archive, name, self.job)
                except (ValueError, UnicodeDecodeError):
                    self.block(name, 0, "legacy UI encoding or record structure could not be audited")
                else:
                    self.ui_audits.append(audit)
                    self.block(name, 0, "legacy UI layout, bindings, and events need equivalent conversion",
                               {key: value for key, value in audit.items() if key != "records"})
        if len(self.images) > 256:
            raise ValueError("converted resources exceed current limit")
        width, height = 720, 1280
        if "强制分辨率.txt" in self.archive.entries:
            resolution = re.fullmatch(r"\s*([0-9]+)x([0-9]+)\s*", self.archive.text("强制分辨率.txt"))
            if resolution and all(100 <= int(number) <= 8192 for number in resolution.groups()):
                width, height = map(int, resolution.groups())
            else:
                self.block("强制分辨率.txt", 0, "resolution format or bounds unsupported")
        # Global gaps also prevent editor direct-run, which otherwise could bypass an unconverted UI.
        if self.blockers:
            sources[selected].insert(0, {"flowSchemaVersion": 1, "nodeId": "legacy-conversion-gate",
                "blockId": "root-" + self.flows[selected], "parentId": None, "orderKey": "A0",
                "kind": "legacy.unsupported", "nodeVersion": 1, "depth": 0,
                "args": {"reason": "conversion contains unresolved semantics; consult sibling report.json"}})
        capabilities = {"core.task"}
        if any(kind.startswith("input.") for kind in self.mapped):
            capabilities.add("input.basic")
        manifest = {"formatVersion": 2, "flowSchemaVersion": 1, "runtimeApi": "1.7",
                    "projectId": stable_id("legacy-", self.archive.name), "name": self.archive.name[:128],
                    "sourceMode": "visual", "entryFlowId": self.flows[selected],
                    "flows": [{"flowId": flow_id, "path": "visual/flows/" + flow_id + ".jsonl",
                               "rootBlockId": "root-" + flow_id, "params": [], "returns": None}
                              for flow_id in self.flows.values()],
                    "variables": declarations,
                    "resources": [{"kind": "image", "path": path} for path in self.images.values()],
                    "capabilities": sorted(capabilities),
                    "design": {"width": width, "height": height, "scaleMode": "letterbox", "orientationPolicy": "follow"}}
        (project / "project.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
        for name, records in sources.items():
            self.job.check()
            path = project / "visual" / "flows" / (self.flows[name] + ".jsonl")
            path.write_text("".join(json.dumps(record, ensure_ascii=False) + "\n" for record in records), encoding="utf-8")
        # Whole original preserves markers, variable aliases, unsupported UI and external libraries.
        (temporary / "original.zip").write_bytes(self.archive.raw_bytes)
        validation = None
        if validator:
            self.job.check("validate", 0, 1)
            validation = validator(project, self.job)
            if not validation.get("compiled") and not self.blockers:
                self.block("compiler", 0, "candidate was rejected by the current compiler")
            self.job.check()
        summary = {"status": "blocked" if self.blockers else "candidate-needs-compiler-validation",
                   "plugins": len(self.flows), "mappedVariables": len(declarations),
                   "convertedImages": len(self.images), "convertedOperations": sum(self.mapped.values()),
                   "convertedActiveOperations": sum(self.mapped.values()) - len(self.disabled),
                   "preservedDisabled": len(self.disabled), "convertedGroups": len(group_aliases),
                   "preservedStructuralMarkers": len(self.structural_markers),
                   "convertedImageDirectories": len(directory_aliases),
                   "unsupportedItems": len(self.blockers), "mappedKinds": dict(self.mapped),
                   "blockingReasons": dict(collections.Counter(item["reason"] for item in self.blockers))}
        from .diagnostics import categorized_counts
        from .requirements import remaining_tasks
        summary["blockingCategories"] = categorized_counts(self.blockers)
        tasks = remaining_tasks(self.blockers)
        summary["requiredFeatures"] = {item["feature"]: item["affectedItems"] for item in tasks}
        summary["uiAudited"] = len(self.ui_audits)
        summary["uiContractGaps"] = dict(collections.Counter(
            gap for audit in self.ui_audits for gap in audit["contractGaps"]))
        from .function_audit import audit_functions
        function_audit = audit_functions(self.blockers, self.job)
        summary["functionAudit"] = function_audit["summary"]
        (temporary / "function-audit.json").write_text(json.dumps(function_audit, ensure_ascii=False, indent=2), encoding="utf-8")
        if validation and validation.get("compiled") and not self.blockers:
            summary["status"] = "converted"
        report = {"version": 1, "summary": summary, "blockers": self.blockers,
                  "variableAliases": self.aliases, "imageAliases": self.images, "pluginAliases": self.flows,
                  "groupAliases": group_aliases, "imageDirectoryAliases": directory_aliases,
                  "disabledRecords": self.disabled,
                  "structuralMarkers": self.structural_markers,
                  "remainingTasks": tasks,
                  "originalSha256": hashlib.sha256(self.archive.raw_bytes).hexdigest(), "validation": validation}
        (temporary / "ui-audit.json").write_text(json.dumps({"version": 1, "layouts": self.ui_audits},
                                                ensure_ascii=False, indent=2), encoding="utf-8")
        (temporary / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        from .report import write_html
        write_html(temporary / "report.html", report)
        if not self.blockers:
            name = "project.asproject" if summary["status"] == "converted" else "candidate.asproject"
            with zipfile.ZipFile(temporary / name, "w", zipfile.ZIP_DEFLATED) as backup:
                for path in sorted(project.rglob("*")):
                    if path.is_file():
                        self.job.check()
                        backup.write(path, path.relative_to(project).as_posix())
        self.job.check("publish", 1, 1)
        publish_directory(temporary, output, self.job)
        return summary


def publish_directory(temporary, output, job):
    """Publish once; tolerate short Windows scanner locks without overwriting."""
    deadline = time.monotonic() + 2
    while True:
        job.check()
        if output.exists() or output.is_symlink():
            raise FileExistsError("output appeared before publication")
        try:
            temporary.rename(output)
            return
        except PermissionError:
            if time.monotonic() >= deadline:
                raise
            time.sleep(0.05)
