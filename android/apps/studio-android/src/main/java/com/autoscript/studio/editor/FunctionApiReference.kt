package com.autoscript.studio

import com.autoscript.studio.generated.GeneratedFunctionDocumentation
import java.util.Locale

/** Generated contracts, not runnable Lua templates or reversible block source. */
internal object FunctionApiReference {
    private val explicitKinds = mapOf(
        "Screen.capture" to "screen.capture", "Screen.cache" to "screen.capture",
        "Screen.captureSeries" to "screen.series", "Screen.loadImage" to "screen.loadimage",
        "Screen.matchValue" to "vision.result", "Screen.findImages" to "vision.findimage",
        "Screen.recognitionValue" to "vision.readresult",
        "Input.tapScreen" to "input.tap", "Input.pointerDownScreen" to "input.pointerdown",
        "Input.pointerMoveScreen" to "input.pointermove", "Input.swipeScreen" to "input.swipe",
        "Text.repeatText" to "text.repeat",
        "System.elapsedRealtimeMillis" to "system.elapsed", "System.getTimeMillis" to "system.time",
        "System.getScreenSize" to "system.screensize",
        "Floating.getBounds" to "floating.bounds",
        "UI.getValue" to "ui.get", "UI.setValue" to "ui.set",
        "UI.showDialogAndWait" to "ui.dialog", "UI.closeDialog" to "ui.dialogclose",
        "Prompt.show" to "task.runprompt", "Prompt.toast" to "task.prompt",
        "Prompt.setEnabled" to "task.promptenabled", "Log.write" to "task.log",
        "Log.setEnabled" to "task.logenabled",
    )

    private fun relatedKind(api: String): String = explicitKinds[api] ?: when (api.substringBefore('.')) {
        "Screen" -> if (api in setOf("Screen.release", "Screen.crop", "Screen.transform", "Screen.savePng"))
            api.lowercase(Locale.ROOT) else "vision." + api.substringAfter('.').lowercase(Locale.ROOT)
        "Apps" -> "app." + api.substringAfter('.').lowercase(Locale.ROOT)
        else -> api.lowercase(Locale.ROOT)
    }

    private val aliasesByKind by lazy {
        GeneratedFunctionDocumentation.all.groupBy { relatedKind(it.name) }
            .mapValues { (_, docs) -> docs.map { it.name } }
    }

    fun aliasesForKind(kind: String): List<String> = aliasesByKind[kind].orEmpty()

    val groups: List<FunctionLibraryGroup> by lazy {
        GeneratedFunctionDocumentation.all.map { doc ->
            val kind = relatedKind(doc.name)
            // UI.waitEvent, Input.resolvePoint and dictionary lifecycle are low-level contracts,
            // not standalone insertable blocks. Keep their documentation without inventing a route.
            val group = functionGroup(kind)
            group to FunctionLibraryEntry(
                title = titles[doc.name] ?: doc.name,
                detail = doc.summary,
                snippet = "",
                blockKind = null,
                requiredCapabilities = setOf(doc.capability),
                parameters = doc.parameters.map { it.name },
                parameterInfo = doc.parameters.map {
                    FunctionParameterInfo(it.name, functionValueType(it.type), it.required, it.summary)
                },
                result = "${functionValueType(doc.returnType)}${if (doc.returnNullable) " / nil" else ""} · ${doc.returnSummary}",
                notes = "底层 API 契约，仅供查阅，不可直接加入积木，也不是插件源码语法。资源句柄、任务及事件 API 需由相应的可视化配置流程管理。",
                keywords = listOf(doc.name),
                apiName = doc.name,
                since = doc.since,
                section = functionSection(kind),
            )
        }.groupBy({ it.first }, { it.second })
            .toSortedMap(compareBy<String> { FUNCTION_GROUP_ORDER.indexOf(it) }.thenBy { it })
            .map { (label, entries) ->
                FunctionLibraryGroup(label, entries.sortedWith(compareBy(
                    { functionSectionOrder(relatedKind(requireNotNull(it.apiName))) }, { it.title }, { it.apiName })))
            }
    }

    private val titles = mapOf(
        "Data.length" to "集合 / 对象长度", "Data.get" to "取集合 / 对象标量",
        "Data.set" to "设置集合 / 对象标量", "Data.remove" to "删除集合 / 对象项",
        "Data.append" to "追加集合标量", "Data.keys" to "列举集合 / 对象键", "Data.text" to "解码 JSON 字符串",
        "Files.read" to "读取项目数据文件", "Files.write" to "写入项目数据文件",
        "Files.list" to "列举项目数据文件", "Files.remove" to "删除项目数据文件",
        "UI.waitEvent" to "等待界面事件", "UI.getValue" to "读取页面参数", "UI.setValue" to "设置控件值", "UI.command" to "界面操作",
        "UI.confirm" to "确认对话框", "UI.showDialogAndWait" to "布局对话框", "UI.closeDialog" to "关闭对话框",
        "Task.sleep" to "等待", "Task.spawn" to "启动子任务", "Task.cancel" to "取消子任务",
        "Task.stop" to "停止脚本", "Task.restart" to "重新运行脚本", "Log.setEnabled" to "脚本日志开关", "Prompt.setEnabled" to "脚本提示开关",
        "Input.record" to "录制全局动作", "Input.playRecording" to "回放录制动作",
        "Audio.play" to "播放音频", "Audio.stop" to "停止音频", "Audio.getVolume" to "读取音乐音量", "Audio.setVolume" to "设置音乐音量",
        "Timer.every" to "启动定时器", "Timer.cancel" to "取消定时器",
        "Input.tap" to "点击", "Input.swipe" to "滑动", "Input.keyEvent" to "设备按键",
        "Input.pointerDown" to "按下", "Input.pointerMove" to "移动", "Input.pointerUp" to "弹起",
        "Input.tapScreen" to "屏幕像素点击", "Input.pointerDownScreen" to "屏幕像素按下",
        "Input.resolvePoint" to "解析屏幕坐标", "Input.pointerMoveScreen" to "屏幕像素移动", "Input.swipeScreen" to "屏幕像素滑动",
        "Screen.capture" to "截图", "Screen.cache" to "缓存帧", "Screen.release" to "释放帧",
        "Screen.captureSeries" to "连续截图", "Screen.loadImage" to "载入图片", "Screen.crop" to "裁剪帧",
        "Screen.getColor" to "取点颜色", "Screen.compareColor" to "单点比色", "Screen.findColor" to "区域找色",
        "Screen.findAllColor" to "全部找色", "Screen.countColor" to "颜色计数", "Screen.findMultiColor" to "多点找色",
        "Screen.findImage" to "区域找图", "Screen.findImages" to "多模板找图", "Screen.findAllImages" to "多结果找图", "Screen.matchValue" to "读取匹配结果", "Screen.findGray" to "灰度找图",
        "Ocr.loadDictionary" to "载入字库", "Ocr.releaseDictionary" to "释放字库", "Ocr.glyph" to "字库识字",
        "Ocr.alphanumeric" to "字母数字识别", "Prompt.show" to "运行提示", "Prompt.toast" to "弹出提示",
        "Screen.transform" to "图像变换", "Screen.savePng" to "保存帧为PNG",
        "Screen.recognitionValue" to "读取识别结果属性",
        "Screen.findAllMultiColor" to "批量多点找色", "Screen.components" to "颜色连通块",
        "Ocr.glyphDetails" to "字库逐字结果", "Ocr.findText" to "从字库结果找文字",
        "Log.write" to "写入日志", "Legacy.duoDianZhaoSe" to "旧版多点找色", "Legacy.duoDianBiSe" to "旧版多点比色",
        "Legacy.getRectColorNum" to "旧版区域颜色数", "Legacy.getRgbColor" to "RGB 转颜色值",
        "Math.distance" to "两点距离", "System.getScreenSize" to "屏幕尺寸",
        "System.elapsedRealtimeMillis" to "单调运行时间",
        "Math.randInt" to "随机整数",
        "Files.append" to "追加数据文件文本",
        "Files.exists" to "数据文件是否存在",
        "Clipboard.getText" to "读取剪贴板",
        "Input.pasteText" to "粘贴文本（含中文）",
        "Clipboard.setText" to "写入剪贴板",
        "Apps.info" to "查询App信息",
        "Apps.open" to "启动App",
        "Floating.show" to "显示/更新浮窗",
        "Floating.hide" to "隐藏浮窗",
        "Floating.close" to "关闭浮窗",
        "Floating.getBounds" to "读取浮窗位置",
        "Files.zip" to "压缩数据目录",
        "Files.unzip" to "解压数据ZIP",
        "Files.getSize" to "数据文件大小",
        "Files.getExtension" to "数据文件扩展名",
        "Files.directoryStats" to "数据目录统计",
        "Files.toBase64" to "文件转Base64",
        "Files.fromBase64" to "Base64保存文件",
        "Files.isDir" to "数据目录是否存在",
        "Files.mkdir" to "创建数据目录",
        "Files.copy" to "复制数据文件",
        "Files.move" to "移动或改名数据文件",
        "Files.rmdir" to "删除空数据目录",
        "Files.readLine" to "读取数据文件一行",
        "Files.insertLine" to "插入数据文件一行",
        "Files.deleteLine" to "删除数据文件一行",
        "Files.lineCount" to "读取数据文件行数",
        "Files.writeLine" to "替换数据文件一行",
        "Files.kvSet" to "保存持久对象值",
        "Files.kvGet" to "读取持久对象值",
        "Files.kvRemove" to "移除持久对象键",
        "Files.kvKeys" to "列举持久对象键",
        "Files.kvAdd" to "持久整数值累加",
        "Text.length" to "文本字符数",
        "Text.contains" to "文本包含判断",
        "Text.startsWith" to "文本开头判断",
        "Text.endsWith" to "文本结尾判断",
        "Text.count" to "文本出现次数",
        "Text.split" to "拆分文本",
        "Text.join" to "拼接文本数组",
        "Text.left" to "取文本左侧",
        "Text.right" to "取文本右侧",
        "Text.sub" to "截取文本",
        "Text.insert" to "插入文本",
        "Text.remove" to "移除文本片段",
        "Text.replace" to "替换全部文本",
        "Text.replaceFirst" to "替换首个文本",
        "Text.repeatText" to "重复文本",
        "Text.between" to "取两个标记之间",
        "Text.before" to "取标记之前",
        "Text.after" to "取标记之后",
        "Text.trim" to "去除首尾空白",
        "Text.find" to "查找文本位置",
        "Text.lower" to "转小写",
        "Text.upper" to "转大写",
        "Text.regexMatch" to "正则匹配判断",
        "Text.regexExtract" to "正则提取首项",
        "Text.regexAll" to "正则提取全部",
        "Data.valid" to "检查JSON合法性",
        "Data.type" to "读取JSON类型",
        "Data.has" to "JSON键是否存在",
        "Data.getJson" to "读取JSON子结构",
        "Date.format" to "格式化日期时间",
        "Date.addDays" to "日期增加天数",
        "Date.daysBetween" to "计算时间差天数",
        "Math.randStr" to "随机字符串",
        "Math.round" to "四舍五入",
        "Math.clamp" to "限制范围",
        "Math.inRange" to "范围判断",
        "Math.angle" to "两点角度",
        "Math.flipAngle" to "翻转角度",
        "Math.joystickX" to "轮盘X坐标",
        "Math.joystickY" to "轮盘Y坐标",
        "Math.red" to "提取红色通道",
        "Math.green" to "提取绿色通道",
        "Math.blue" to "提取蓝色通道",
        "System.getTimeMillis" to "读取Unix时间",
    )
}
