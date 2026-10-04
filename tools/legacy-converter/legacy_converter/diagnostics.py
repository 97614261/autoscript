"""Stable categories and local-only source/node inspection."""
import collections
import hashlib
import json

CATEGORY_LABELS = {
    "entry": "入口待确认", "input": "原始素材问题", "organization": "工程组织",
    "vision": "找图与结果对象", "loops": "循环", "conditions": "判断条件",
    "variables": "变量与表达式", "prompts": "运行提示与日志", "calls": "插件调用与返回",
    "interface": "界面与事件", "functions": "函数映射", "external": "外部库",
    "disabled": "禁用容器", "branch-markers": "结果分支标记", "compiler": "编译校验",
    "unknown": "其他待核对",
}


def category(reason, record):
    if reason.startswith("entry plugin"):
        return "entry"
    if "BMP" in reason:
        return "input"
    if "external APK/DEX" in reason:
        return "external"
    if "legacy UI" in reason:
        return "interface"
    if "group" in reason or "directory" in reason:
        return "organization"
    if "result-object" in reason:
        return "vision"
    if "compiler" in reason:
        return "compiler"
    record = record or {}
    if record.get("_注释步骤类型"):
        return "disabled"
    if record.get("_函数名字"):
        return "functions"
    if record.get("_关键字"):
        return "conditions"
    if record.get("_循环检测") or record.get("_循环尾"):
        return "loops"
    kinds = {"_寻图": "vision", "_循环": "loops", "_语法": "variables",
             "_运行提示": "prompts", "_插件": "calls", "_跳转": "calls", "_按键": "functions"}
    if record.get("_步骤类型") in kinds:
        return kinds[record["_步骤类型"]]
    if record.get("_显示") in {"寻图成功", "寻图失败", "运行成功", "运行失败", "找色成功", "找色失败"}:
        return "branch-markers"
    return "unknown"


def categorized_counts(issues):
    return dict(collections.Counter(item["category"] for item in issues))


def inspect_issue(output, issue, plugin_aliases):
    """Private content is returned to the detail UI only, never emitted in logs."""
    original = json.dumps(issue.get("record"), ensure_ascii=False, indent=2)
    converted = "此项没有对应转换节点（工程配置或资源问题）。"
    if issue.get("category") == "interface":
        path = output / "ui-audit.json"
        if path.is_file() and path.stat().st_size <= 32 * 1024 * 1024:
            if path.resolve().parent != output.resolve():
                raise ValueError("inspection path escapes output directory")
            for layout in json.loads(path.read_text(encoding="utf-8"))["layouts"]:
                if layout["source"] == issue["source"]:
                    original = json.dumps(layout["records"], ensure_ascii=False, indent=2)
                    converted = "界面尚未迁移。契约缺口：\n" + json.dumps(layout["contractGaps"], ensure_ascii=False)
                    break
    flow_id = plugin_aliases.get(issue["source"])
    if flow_id:
        # Identity is generated, not an arbitrary path supplied by the report.
        expected = "flow-" + hashlib.sha256(issue["source"].encode("utf-8")).hexdigest()[:24]
        if flow_id != expected:
            raise ValueError("report flow identity invalid")
        path = output / "project-draft/visual/flows" / (flow_id + ".jsonl")
        if path.resolve().parent != (output / "project-draft/visual/flows").resolve():
            raise ValueError("inspection path escapes flow directory")
        if path.stat().st_size > 64 * 1024 * 1024:
            raise ValueError("inspection exceeds budget")
        identity = "node-" + hashlib.sha256((issue["source"] + ":" + str(issue["line"])).encode("utf-8")).hexdigest()[:24]
        with path.open(encoding="utf-8") as stream:
            for line in stream:
                node = json.loads(line)
                if node.get("nodeId") == identity:
                    converted = json.dumps(node, ensure_ascii=False, indent=2)
                    break
    return original, converted
