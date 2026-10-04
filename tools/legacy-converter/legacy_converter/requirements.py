"""Evidence-backed remaining work. This diagnoses gaps; it never executes legacy code."""
import collections
import re

# Titles and instructions are trusted, fixed text: no private identifiers in aggregate logs.
TASKS = {
    "entry": (1, "入口确认", "用户选择", "明确实际入口插件；不从名称或文件顺序猜测。"),
    "asset": (1, "原图片修复", "原始素材", "补充完整原图片，不能用空白像素冒充修复。"),
    "organization": (1, "组织信息校验", "转换规则", "处理缺失成员、重复归属、目录深度和数量；保持备份兼容。"),
    "plugin-missing": (1, "缺失调用目标", "原始素材", "核对原路径及缺失插件，不能改调用目标或创建空插件冒充。"),
    "disabled": (2, "禁用容器", "旧实现证据", "确认注释父容器是否同时跳过子树；未确认前保留并阻断。"),
    "text-bytes": (2, "旧字符串字节语义", "契约适配", "旧 Length/Mid/Find 使用字节及0起点；不能直接套字符数和1起点函数。"),
    "expressions": (2, "表达式与类型转换", "转换规则", "核对除法、余数、整数截断、数值转字符、自增及函数调用；逐项测试。"),
    "variables": (2, "变量作用域及类型", "转换规则", "先修复未声明变量及类型约束，不能创建隐式变量掩盖错误。"),
    "prompt": (2, "提示表达式及标题", "转换／契约", "保留标题、表达式、显示方式和消失行为，不能只写应用日志。"),
    "file-log": (2, "脚本文件日志", "转换规则", "旧文字记录有追加和路径语义；只允许工程数据目录，不能替换为开发日志。"),
    "repeat": (3, "循环次数表达式", "转换规则", "先明确计算类型和求值时机，再转换次数；拒绝超限。"),
    "infinite": (3, "真正无限循环", "契约适配", "需可取消、超时策略和调度让步；固定100万次不是等价无限循环。"),
    "timed": (3, "限时循环", "转换／契约", "核对秒单位、截止检测时机及中断中的输入释放，不能附加假次数上限。"),
    "metrics": (3, "循环指标及检测", "旧实现证据", "核对次数起点、时间单位及最近循环阈值，支持嵌套。"),
    "vision": (4, "找图算法及后点击", "转换／契约", "核对普通、多组、滤色、找色、方向、阈值、ROI、点击偏移及前后等待。"),
    "result-object": (4, "多匹配结果对象", "契约适配", "旧结果可有多个匹配，含数量、坐标、宽高、分数、名称；不能当屏幕帧或单坐标。"),
    "branches": (4, "成功失败分支", "转换规则", "依据找图或调用的实际结果分别执行；不能把两条子树依次运行。"),
    "conditions": (4, "复合条件及结果属性", "转换／契约", "核对短路AND/OR、括号、属性与比较类型，失败保留整个条件链。"),
    "calls": (5, "插件调用和状态返回", "转换／契约", "确认正常结束的默认状态、显式成功失败、参数及调用帧；void返回不等价。"),
    "ui": (6, "界面布局、绑定及事件", "转换／契约", "解码可读pz，转换容器和控件，并逐项处理当前UI契约不支持的样式和事件。"),
    "functions": (7, "函数签名逐项迁移", "旧实现证据", "确认参数、返回值、索引和副作用后接入强类型API；不能按同名猜测。"),
    "external": (7, "外部库替代", "旧实现证据", "仅静态分析，明确等价实现后窄适配；不执行DEX/APK，不开放shell。"),
    "compiler": (8, "编译与运行验收", "验证", "无未迁移项且当前编译器通过才输出最终工程，设备验收另行进行。"),
    "unknown": (7, "剩余指令核对", "旧实现证据", "保留原字段和树结构，核对旧公开签名或Native实现。"),
}


def feature(issue):
    values = issue.get("record") or {}
    reason = issue["reason"]
    category = issue.get("category", "unknown")
    if reason == "referenced plugin is missing":
        return "plugin-missing"
    if category == "variables":
        expression = values.get("_赋值为", "") + values.get("_属性类型", "")
        if re.search(r"\.(Length|Find|Mid|Rep|Add|Del|MidLeft|MidRight|MidCentre)\b|字符长度|查找字符|截取字符", expression):
            return "text-bytes"
        return "variables" if any(text in reason for text in ("undeclared", "variable type", "declaration type")) else "expressions"
    if category == "loops":
        kind = values.get("_循环类型")
        return {"_无限循环": "infinite", "_限时循环": "timed", "_限次循环": "repeat",
                "_循环次数": "metrics", "_循环时间": "metrics"}.get(kind, "metrics")
    if category == "prompts":
        return "file-log" if values.get("_提示类型") == "_文字记录" else "prompt"
    if "result-object" in reason:
        return "result-object"
    return {"input": "asset", "branch-markers": "branches", "interface": "ui"}.get(category,
        category if category in TASKS else "unknown")


def remaining_tasks(issues):
    counts = collections.Counter()
    for issue in issues:
        key = feature(issue)
        issue["requiredFeature"] = key
        counts[key] += 1
    result = []
    for key in sorted(counts, key=lambda value: (TASKS[value][0], value)):
        phase, title, owner, action = TASKS[key]
        result.append({"feature": key, "phase": phase, "title": title, "owner": owner,
                       "affectedItems": counts[key], "action": action})
    return result


UI_TYPES = {"_窗体", "_按钮", "_单选框", "_文本框", "_列表框", "_组框", "_标签",
            "_复选框", "_图像", "_下拉列表", "_TAB容器", "_浏览器"}
UI_EVENTS = {"_左键单击事件", "_左键按下事件", "_左键弹起事件", "_鼠标移动事件",
             "_窗体加载完成", "_窗体卸载", "_获得焦点", "_失去焦点", "_选择改变事件",
             "_文本改变事件", "_页面加载完成事件", "_JS调用一键玩事件"}
STYLE_FIELDS = {"_字体加粗", "_下划线", "_样式模版", "_列表项高度", "_边框色",
                "_右侧三角色", "_底部线条色", "_组框边框颜色", "_未选择项颜色",
                "_选择字体色", "_选择项颜色"}
UNMAPPED_EVENTS = UI_EVENTS - {"_左键单击事件", "_选择改变事件", "_文本改变事件"}


def audit_ui(archive, source, job):
    """Private raw fields remain local; aggregate keys use only known protocol constants."""
    from .core import fields, record_lines
    records, types, events, gaps = [], collections.Counter(), collections.Counter(), set()
    controls = 0
    for number, line in enumerate(record_lines(archive.text(source)), 1):
        job.check()
        if not line:
            continue
        if len(records) >= 4096:
            raise ValueError("UI record count exceeds budget")
        values = fields(line)
        records.append({"line": number, "record": values})
        kind = values.get("_ID类型")
        types[kind if kind in UI_TYPES else "<other>"] += 1
        if kind != "_窗体":
            controls += 1
        if kind == "_浏览器":
            gaps.add("web-control")
        if kind in {"_按钮", "_单选框", "_文本框", "_列表框", "_标签", "_复选框", "_下拉列表"}:
            # myView.SetFontSize calls TextView.setTextSize(float): SP * sizeZoom, not PX.
            gaps.add("font-sp-to-px")
        if kind == "_TAB容器" or values.get("_容器分组分页") not in (None, "", "0"):
            gaps.add("tab-and-container-groups")
        if any(values.get(key) not in (None, "", "0") for key in STYLE_FIELDS):
            gaps.add("extended-styles")
        for key in UI_EVENTS:
            if values.get(key) not in (None, "", "0"):
                events[key] += 1
                if key in UNMAPPED_EVENTS:
                    gaps.add("additional-events")
        for key in ("_宽", "_高"):
            value = values.get(key, "")
            if value and (not value.isascii() or not value.isdigit() or int(value) < 20):
                gaps.add("control-geometry")
        alignment = values.get("_对齐方式")
        if alignment and alignment not in ("3", "5", "17"):
            gaps.add("gravity-alignment")
    if controls > 256:
        gaps.add("ui-field-limit-256")
    return {"source": source, "controlCount": controls, "types": dict(types), "events": dict(events),
            "contractGaps": sorted(gaps), "records": records, "status": "not-converted"}
