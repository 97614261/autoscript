"""Build a non-executing, catalog-complete visual project; never touch a device."""
import argparse
import io
import json
import pathlib
import re
import secrets
import struct
import time
import wave
import zipfile
import zlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
IMAGE = "assets/images/example.png"
DICTIONARY = "dictionaries/digits.asglyph"
AUDIO = "assets/audio/example.wav"
GROUPS = [
    ("input", "01-触控与按键"), ("control", "02-条件与循环"),
    ("flow", "03-插件与任务"), ("task", "04-提示与日志"),
    ("variable", "05-变量与数学"), ("text", "06-文本处理"),
    ("data", "07-集合与JSON"), ("files", "08-项目数据文件"),
    ("screen", "09-截图与图像帧"), ("vision", "10-图像与颜色识别"),
    ("ocr", "11-文字与字库"), ("ui", "12-脚本界面"),
    ("floating", "13-交互浮窗"), ("system", "14-设备与应用"),
    ("date", "15-日期与路径"), ("legacy", "16-旧版兼容积木"),
]


def json_text(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def group_for(kind):
    family = kind.split(".")[0]
    if kind in ("task.spawn", "task.cancel") or family == "timer":
        return "flow"
    if family == "math":
        return "variable"
    if family in ("app", "audio", "clipboard", "network"):
        return "system"
    if family == "path":
        return "date"
    return family


def utility_types():
    source = (ROOT / "android/apps/studio-android/src/main/java/com/autoscript/studio/editor/VisualUtilityArguments.kt").read_text(encoding="utf-8")
    body = source.split("internal fun utilityResultType", 1)[1].split("private val utilityBounds", 1)[0]
    result = {}
    for names, value_type in re.findall(r'((?:\s*"[a-z0-9.]+"\s*,?)+)\s*-> ProjectVariableType\.(\w+)', body):
        for name in re.findall(r'"([a-z0-9.]+)"', names):
            result[name] = value_type.lower()
    return result


class Example:
    def __init__(self):
        self.catalog = {block["kind"]: block for path in sorted((ROOT / "schema/block-catalog/blocks").glob("*.json"))
                        for block in [json.loads(path.read_text(encoding="utf-8"))]}
        self.types = utility_types()
        self.variables = {}
        self.flows = []
        self.rows = {}
        self.sequence = 0
        self.coverage = {}
        for name, kind in [("演示开关", "integer"), ("次数", "integer"), ("数值", "number"),
                           ("文本", "string"), ("原图", "image"), ("对照图", "image"),
                           ("结果集合", "string"), ("系列帧", "string"), ("录制数据", "string"),
                           ("任务编号", "integer"), ("调用参数", "string")]:
            self.declare(name, kind)

    def declare(self, name, kind):
        if name in self.variables and self.variables[name] != kind:
            raise ValueError(f"Variable type collision: {name}")
        self.variables[name] = kind
        return name

    def flow(self, flow_id, title):
        self.flows.append(dict(flowId=flow_id, path=f"visual/flows/{title}.jsonl", rootBlockId=f"root-{flow_id}", params=[], returns=None))
        self.rows[flow_id] = []

    def output_type(self, kind, key, label):
        if key == "idVariable" or kind in ("task.spawn", "timer.every", "data.length"):
            return "integer"
        if key in ("frameVariable", "previousFrameVariable", "imageVariable"):
            return "image"
        if key in ("xVariable", "yVariable", "foundVariable", "countVariable", "scoreVariable", "coverageVariable", "widthVariable", "heightVariable", "indexVariable", "iterationVariable", "elapsedVariable"):
            return "integer"
        if key == "textVariable" or key in ("templateVariable", "sourceVariable", "detailsVariable", "idVariable", "recordingVariable"):
            return "string"
        if kind in ("screen.capture", "screen.captureregion", "screen.crop", "screen.loadimage", "screen.seriesframe", "screen.transform"):
            return "image"
        if kind in ("vision.result", "vision.readresult", "vision.comparecolor", "vision.getcolor", "vision.countcolor", "legacy.duodianbise", "legacy.getrectcolornum", "legacy.getrgbcolor", "audio.getvolume"):
            return "integer"
        if "图像变量" in label:
            return "image"
        if kind in self.types:
            return self.types[kind]
        if kind.startswith("math."):
            return "number"
        return "string"

    def arguments(self, kind):
        contract = self.catalog[kind]
        args = {}
        variable_fields = {"resultVariable", "targetVariable", "frameVariable", "previousFrameVariable", "sourceVariable", "detailsVariable",
                           "foundVariable", "countVariable", "xVariable", "yVariable", "scoreVariable", "coverageVariable", "textVariable", "widthVariable", "heightVariable",
                           "recordingVariable", "idVariable"}
        for prop in contract["properties"]:
            key, editor = prop["path"], prop["editor"]
            if not prop["required"]:
                continue
            if key in variable_fields:
                source_names = {"frameVariable": "原图", "previousFrameVariable": "对照图", "sourceVariable": "结果集合",
                                "detailsVariable": "结果集合", "recordingVariable": "录制数据", "idVariable": "任务编号"}
                if kind.startswith("screen.series") and key == "sourceVariable":
                    value = "系列帧"
                else:
                    stem = key[:-8] if key.endswith("Variable") else key
                    value = source_names.get(key, f"结果_{self.sequence + 1}_{stem}")
                args[key] = self.declare(value, self.output_type(kind, key, prop["label"]))
                continue
            value = prop.get("defaultValue")
            if editor in ("integer", "integer_enum", "number", "boolean", "scalar"):
                args[key] = json.loads(value if value not in (None, "") else "0")
            elif editor == "rect":
                args[key] = dict(left=0, top=0, right=64, bottom=64)
            elif editor == "legacy_region":
                args[key] = dict(left=0, top=0, width=64, height=64)
            elif editor == "point":
                args[key] = dict(x=24, y=24)
            elif editor == "color":
                args[key] = 0xFF3388
            elif editor == "multi_color_samples":
                args[key] = [dict(x=1, y=0, rgb=0xFF3388, tolerance=10)]
            elif editor in ("legacy_pattern", "legacy_fixed_pattern"):
                coordinates = dict(dx=0, dy=0) if editor == "legacy_pattern" else dict(x=24, y=24)
                args[key] = [dict(**coordinates, rgb=0xFF3388, toleranceRed=5, toleranceGreen=5, toleranceBlue=5)]
            elif editor == "legacy_color_group":
                args[key] = [dict(rgb=0xFF3388, toleranceRed=5, toleranceGreen=5, toleranceBlue=5)]
            elif editor == "flow_reference":
                args[key] = "worker"
            elif editor == "flow_arguments":
                args[key] = {}
            elif editor == "resource_list":
                args[key] = [IMAGE]
            elif editor == "resource":
                args[key] = DICTIONARY if prop.get("resourceKind") == "glyph_dictionary" else IMAGE
            else:
                args[key] = value if value is not None else ""
        if "imagePath" in args:
            args["imagePath"] = IMAGE
        if kind == "audio.play":
            args["path"] = AUDIO
        if kind in ("ocr.glyph", "ocr.glyphdetails"):
            args["dictionaryPath"] = DICTIONARY
        if kind in ("flow.argument.set", "flow.return.set"):
            args["value"] = 3
        if kind == "floating.show":
            args.update(text="智构示例浮窗", imagePath="", shapesJson="[]")
        if kind == "floating.image":
            args["imagePath"] = IMAGE
        if kind in ("variable.set", "variable.copy", "variable.calculate"):
            args["name"] = "次数"
            if kind == "variable.copy":
                args["sourceName"] = "次数"
            elif kind == "variable.set":
                args["value"] = 3
            else:
                args["expression"] = "次数 + 1"
        if kind in ("control.if", "control.while"):
            args.update(variable="次数", operator="lessThan", value=3)
        if kind in ("control.goto", "control.label"):
            args["name"] = "示例位置"
        if kind == "control.loopmetric":
            args["name"] = self.declare("循环读数", "integer")
        if kind.startswith("task.") and "message" in args:
            args["message"] = f"{contract['title']}：智构示例"
        if kind == "task.message":
            args["expression"] = '"当前次数：" .. text(次数)'
        if kind in ("ui.get", "ui.set", "ui.command"):
            args["controlId"] = "status"
            if kind == "ui.command":
                args.update(operation="text", value="来自积木的更新")
        if kind == "ui.dialog":
            args["pageId"] = "main"
        if kind == "ui.confirm":
            args.update(title="操作确认", message="是否继续执行这个示例？")
        if kind == "screen.mappoint":
            args["xVariable"] = ""
            args["yVariable"] = ""
        if kind == "screen.savepng":
            args["path"] = "全积木示例/screenshot.png"
        if kind == "ocr.findtext":
            args["text"] = "123"
        if kind == "vision.resultaction":
            args.update(screenConfirmed=True, selection="first", action="tap")
        if kind == "files.frombase64":
            args["base64"] = "5pm65p6E"
        if kind == "network.request":
            args.update(method="GET", url="https://example.com/", headersJson="{}", body="")
        if kind == "clipboard.settext" or kind in ("input.imetext", "input.pastetext"):
            args["text"] = "智构 ABC 123"
        if kind in ("task.sleep",):
            args["milliseconds"] = 100
        if kind == "control.repeat":
            args["times"] = 3
        return args

    def node(self, flow_id, kind, args=None, parent=None, slot=None, disabled=False):
        self.sequence += 1
        node_id = f"n{self.sequence}"
        args = self.arguments(kind) if args is None else args
        if parent is None:
            block = next(flow["rootBlockId"] for flow in self.flows if flow["flowId"] == flow_id)
            depth = 0
        else:
            block, depth = parent["childBlocks"][slot], parent["depth"] + 1
        row = dict(flowSchemaVersion=1, nodeId=node_id, blockId=block, parentId=None if parent is None else parent["nodeId"],
                   depth=depth, orderKey=f"{self.sequence:08d}", kind=kind, nodeVersion=self.catalog[kind]["nodeVersion"], args=args)
        if disabled:
            row["disabled"] = True
        slots = self.catalog[kind]["childBlocks"]
        if slots:
            row["childBlocks"] = {key: f"b{self.sequence}-{key}" for key in slots}
        self.rows[flow_id].append(row)
        self.coverage.setdefault(kind, []).append(node_id)
        return row

    def comment(self, flow_id, message, parent=None, slot=None):
        return self.node(flow_id, "task.comment", {"message": message}, parent, slot)

    def populate(self):
        self.flow("main", "00-开始这里-使用说明")
        for text in ["全积木示例：覆盖当前函数目录的全部218种积木。", "在插件选择中打开01～16分类，查看参数、分支和子积木。",
                     "所有示例区默认注释禁用；按需复制积木到自己的插件，接好变量和资源再运行。",
                     "主插件只演示变量、3次循环和日志，不执行触控、截图、联网、文件修改或系统设置。",
                     "每个结果变量已声明；图像、字库、音频是此项目自带的公开合成资源。"]:
            self.comment("main", text)
        self.node("main", "variable.set", {"name": "次数", "value": 0})
        repeat = self.node("main", "control.repeat", {"times": 3})
        self.node("main", "variable.calculate", {"name": "次数", "expression": "次数 + 1"}, repeat, "body")
        self.node("main", "task.message", {"channel": "console", "expression": '"安全演示第 " .. text(次数) .. " 次"'}, repeat, "body")
        self.node("main", "task.log", {"level": "info", "message": "全积木示例已载入。请打开插件分类查看，不会自动执行示例区。"})
        for family, title in GROUPS:
            self.flow(family, title)
            self.comment(family, "本插件为积木参数示例，不是顺序执行脚本。示例区默认禁用，需按用途单独复制与接线。")
            gate = self.node(family, "control.if", {"variable": "演示开关", "operator": "equals", "value": 1}, disabled=True)
            gate["display"] = dict(alias="示例区（默认禁用，按需复制单行）", showParameters=False)
            loop = None
            for kind in self.catalog:
                if group_for(kind) != family:
                    continue
                if kind in ("control.label", "control.goto"):
                    self.node(family, kind, disabled=True)
                    continue
                parent, slot = gate, "then"
                if kind in ("control.break", "control.continue", "control.loopcheck", "control.loopmetric"):
                    if loop is None:
                        loop = self.node(family, "control.repeat", {"times": 3}, gate, "then")
                    parent, slot = loop, "body"
                example = self.node(family, kind, parent=parent, slot=slot)
                for child in example.get("childBlocks", {}):
                    self.comment(family, {"body": "在这里加入循环中执行的操作", "then": "条件成立时执行", "else": "条件不成立时执行",
                                          "success": "调用或识别成功时执行", "failure": "返回失败或未找到时执行"}.get(child, child), example, child)
            if family == "control":
                timed = self.node(family, "control.cycle", {"yieldMs": 1, "durationMs": 2000}, gate, "then")
                self.comment(family, "限时2秒的循环示例", timed, "body")
                self.node(family, "control.repeat", {"times": 0, "timesVariable": "次数"}, gate, "then")
            if family == "task":
                for channel in ("popup", "output", "console"):
                    self.node(family, "task.message", {"channel": channel, "expression": '"动态内容：" .. text(次数)'}, gate, "then")
            if family == "variable":
                for operation in ("abs", "ceil", "floor", "sqrt", "sin", "deg", "rad"):
                    args = self.arguments("math.unary")
                    args.update(operation=operation, value=4)
                    self.node(family, "math.unary", args, gate, "then")
            if family == "ui":
                self.node(family, "ui.command", {"controlId": "status", "operation": "get:text", "value": "", "resultVariable": "文本"}, gate, "then")
            if family == "flow":
                self.node(family, "flow.status.return", {"success": False}, gate, "then")
        self.flow("worker", "17-被调用插件示例")
        self.comment("worker", "供调用插件、成功失败分支、任务和定时器示例引用；不访问外部应用。")
        self.node("worker", "flow.status.return", {"success": True})
        assert set(self.coverage) == set(self.catalog), sorted(set(self.catalog) - set(self.coverage))

    def manifest(self, project_id):
        ui = json.loads((ROOT / "examples/native-workflow-project/project.json").read_text(encoding="utf-8"))["runnerUi"]
        ui["description"] = "全积木示例的界面资源；主插件只输出安全演示日志。"
        for field in ui["fields"]:
            field["ui"].pop("binding", None)
        ui["fields"][0]["initialValue"] = "智构 · 全积木示例"
        ui["fields"][1]["initialValue"] = 3
        return dict(formatVersion=2, flowSchemaVersion=1, runtimeApi="1.8", projectId=project_id,
                    name="全积木示例 · 218种", sourceMode="visual", entryFlowId="main", flows=self.flows,
                    variables=[dict(name=name, scope="global", type=kind) for name, kind in self.variables.items()],
                    resources=[dict(kind="image", path=IMAGE), dict(kind="glyphDictionary", path=DICTIONARY), dict(kind="audio", path=AUDIO)],
                    capabilities=sorted({"core.task"} | {c for block in self.catalog.values() for c in block["requiredCapabilities"]}),
                    design=dict(width=720, height=1280, scaleMode="letterbox", orientationPolicy="follow"),
                    debugSettings=dict(runDelayMs=0, showRunPrompts=True, traceExecution=True, recordVisionMetrics=True), runnerUi=ui)


def resources():
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    pixels = b"".join(b"\0" + b"".join(bytes((255, 51, 136, 255)) if (x // 8 + y // 8) % 2 else bytes((245, 245, 255, 255)) for x in range(64)) for y in range(64))
    image = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 64, 64, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(pixels)) + chunk(b"IEND", b"")
    patterns = ["111101101101111", "010110010010111", "111001111100111", "111001111001111", "101101111001001", "111100111001111", "111100111101111", "111001001001001", "111101111101111", "111101111001111"]
    dictionary = b"ASGLYPH\0" + struct.pack("<HHI", 1, 0, 10)
    for digit, bits in enumerate(patterns):
        packed = int(bits + "0", 2).to_bytes(2, "big")
        dictionary += struct.pack("<HHHHI", 1, 3, 5, 0, 2) + str(digit).encode("ascii") + packed
    audio = io.BytesIO()
    with wave.open(audio, "wb") as wav:
        wav.setparams((1, 2, 8000, 0, "NONE", "not compressed"))
        wav.writeframes(b"\0\0" * 800)
    return {IMAGE: image, DICTIONARY: dictionary, AUDIO: audio.getvalue()}


def build(output, project_id=None):
    project_id = project_id or "project-" + secrets.token_hex(4)
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}", project_id):
        raise ValueError("Invalid project ID")
    example = Example()
    example.populate()
    manifest = example.manifest(project_id)
    output.mkdir(parents=True, exist_ok=False)
    project = output / project_id
    project.mkdir()
    files = {"project.json": (json_text(manifest) + "\n").encode("utf-8"), **resources()}
    for flow in example.flows:
        files[flow["path"]] = ("\n".join(json_text(row) for row in example.rows[flow["flowId"]]) + "\n").encode("utf-8")
    files[".studio/source-groups.json"] = (json_text(dict(version=1, groups=[dict(name="全部积木分类", flowIds=[key for key, _ in GROUPS])])) + "\n").encode("utf-8")
    for path, content in files.items():
        target = project / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
    now = int(time.time() * 1000)
    (project / ".studio/state.properties").write_text(f"createdAt={now}\nupdatedAt={now}\n", encoding="ascii")
    archive = output / "all-visual-blocks.asproject"
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as bundle:
        for path, content in files.items():
            bundle.writestr(path, content)
    coverage = dict(projectId=project_id, name=manifest["name"], kinds=len(example.catalog), flows=len(example.flows),
                    nodes=sum(len(rows) for rows in example.rows.values()), variables=len(example.variables),
                    coverage=example.coverage, project=str(project), backup=str(archive))
    (output / "coverage.json").write_text(json.dumps(coverage, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in coverage.items() if key != "coverage"}, ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--project-id")
    options = parser.parse_args()
    build(options.output.resolve(), options.project_id)
