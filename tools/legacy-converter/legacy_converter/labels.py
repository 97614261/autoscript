"""Trusted UI labels, never interpolate private exception payloads into logs."""
import zipfile

REASONS = {
    "calculation string length source must be string": "字节长度需要字符变量，不能按数值或图像处理",
    "rewritten calculation exceeds bounds": "转换后的计算表达式超过1024字节",
    "referenced plugin is missing": "调用目标未找到，需要核对路径或补充原插件",
    "legacy UI encoding or record structure could not be audited": "界面编码或字段结构无法读取，原稿完整保留",
    "repeat literal exceeds current bound": "循环次数超过当前100万次上限",
    "repeat count variable must be integer": "限次循环需要整数变量",
    "timed loop seconds outside exact millisecond bounds": "限时秒数必须精确到毫秒，范围0.001至86400秒",
    "timed loop duration variable must be numeric": "限时循环需要整数或浮点时长变量",
    "infinite loop contains an unexpected duration": "无限循环包含异常时长字段，不能静默丢弃",
    "loop tail marker is misplaced or has executable children": "循环尾位置异常或包含执行内容",
    "disabled container semantics require confirmation": "禁用容器的子步骤处理规则尚未确认",
    "legacy prompt title is not equivalent to the current prompt": "旧提示带标题，不能直接替换为无标题提示",
    "string expression requires typed literal/variable concatenation": "字符串表达式包含函数、属性或尚未支持的语法",
    "string concatenation requires explicit numeric conversion": "字符串与数值连接需要明确转换规则",
    "legacy group membership is invalid, duplicated, or references a missing plugin": "分组重复、归属冲突或引用插件缺失",
    "legacy image directory depth or count exceeds current limits": "图片目录超过4层或256目录上限",
    "legacy image directory alias conflicts with current limits": "图片目录别名冲突或路径超限",
    "condition requires a simple typed comparison": "判断包含复合逻辑、函数或属性，需要等价迁移",
    "comparison operand needs expression translation": "判断值包含尚未支持的表达式",
    "comparison variable types differ": "判断变量类型不同，需要明确转换规则",
    "comparison literal type or bounds differ": "判断值的类型或大小不符合声明",
    "legacy result-object/variable type is not equivalent to a screen frame": "旧寻图结果对象尚未映射（不能当作屏幕帧）",
    "entry plugin must be explicitly confirmed; provisional first plugin is not an entry inference": "需要明确选择入口插件",
    "container/conditional branches need an equivalent control-flow mapping": "此容器或结果分支尚未完成等价迁移",
    "prompt content is an expression, not a quoted literal": "提示内容是表达式，尚未迁移",
    "legacy instruction has no confirmed equivalent yet": "此指令尚未确认等价实现",
    "disabled legacy step retained without reactivation": "禁用步骤已保留，未自动启用",
    "calculation operator/function needs equivalent translation": "此计算运算符或函数尚未迁移",
    "repeat count is not a bounded literal": "循环次数需要变量或表达式迁移",
    "legacy success/failure return status is not a void return": "成功／失败返回状态尚未迁移",
    "calculation variable type differs": "计算变量类型不匹配，需要显式类型迁移",
    "variable is undeclared or has an unsupported type": "变量未声明或类型尚未支持",
    "calculation requires a bounded numeric expression": "此赋值需要非数值表达式迁移",
    "unsupported or invalid BMP layout": "图片格式不支持或像素数据损坏",
    "invalid BMP header": "图片头部损坏",
    "truncated BMP pixels": "图片像素数据不完整",
    "legacy plugin group membership needs confirmed mapping": "插件分组尚未迁移",
    "external APK/DEX library preserved, never executed or translated to arbitrary host commands": "外部 APK／DEX 已保留，但不会执行或自动迁移",
    "legacy UI layout, bindings, and events need equivalent conversion": "旧界面布局、绑定和事件尚未迁移",
    "candidate was rejected by the current compiler": "当前编译器拒绝候选工程，需查看校验结果",
}


def reason_label(reason):
    return REASONS.get(reason, reason)


def failure_message(error):
    if isinstance(error, FileExistsError):
        return "输出已存在，请选择新的输出目录；未覆盖文件。"
    if isinstance(error, PermissionError):
        return "文件访问被拒绝或仍被占用，请检查输出权限和文件占用。"
    if isinstance(error, zipfile.BadZipFile):
        return "压缩包格式损坏或不是有效的ZIP。"
    if isinstance(error, UnicodeDecodeError):
        return "旧工程文本编码不支持，预期为UTF-8。"
    if isinstance(error, TimeoutError):
        return "任务超时，未发布成功产物。"
    if isinstance(error, ValueError):
        return "输入结构、类型或资源超出支持范围，或编译检查器结果无效。"
    if isinstance(error, OSError):
        return "文件读写或启动编译检查器失败，请检查路径和权限。"
    return "工具发生内部错误，未发布成功产物。"
