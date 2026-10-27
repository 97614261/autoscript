package com.autoscript.studio

internal fun ProgramLabelArguments.utilityDetails(kind: String): String? = when (kind) {
    "math.unary" -> parts(variable("operationVariable")?.let { "运算 $it" } ?: if (mathOperationTitle(text("operation")) == null) text("operation") else null,
        input("value"), output())
    "math.binary" -> parts(variable("operationVariable")?.let { "运算 $it" } ?: if (mathOperationTitle(text("operation")) == null) text("operation") else null,
        "${input("left")}, ${input("right")}", output())
    "math.randint" -> parts("${input("min")} 至 ${input("max")}", output())
    "math.clamp", "math.inrange" -> parts(input("value"), "范围 ${input("min")} 至 ${input("max")}", output())
    "math.roundto", "text.formatnumber" -> parts(input("value"), "小数 ${input("digits")} 位", output())
    "math.angle", "math.distance" -> parts("${point("x1", "y1")} → ${point("x2", "y2")}", output())
    "math.joystickx", "math.joysticky" -> parts("中心 ${input(if (kind == "math.joystickx") "centerX" else "centerY")}",
        "角度 ${input("angle")}", "半径 ${input("radius")}", output())
    "math.flipangle" -> parts(input("angle"), variable("modeVariable") ?: when (text("mode")) { "1" -> "上下翻转"; "2" -> "左右翻转"; else -> text("mode")?.let { "模式 $it" } }, output())
    "math.randstr" -> parts("${input("length")} 字符", variable("modeVariable") ?: when (text("mode")) {
        "1" -> "小写字母"; "2" -> "大写字母"; "3" -> "大小写字母"; "4" -> "大小写及数字"; "5" -> "小写及数字"; else -> text("mode")?.let { "模式 $it" }
    }, output())
    "text.filter" -> parts(filterMode(), input("text", quoted = true), output())
    "text.urlencode", "text.urldecode" -> parts(variable("modeVariable") ?: when (text("mode")) { "component" -> "URL组件"; "form" -> "表单"; else -> text("mode") }, input("text", quoted = true), output())
    "data.encode" -> parts(variable("modeVariable") ?: when (text("mode")) { "text" -> "文本编码"; "json" -> "JSON序列化"; else -> text("mode") }, input("value", quoted = true), output())
    "data.sort" -> parts(variable("modeVariable") ?: when (text("mode")) {
        "numberAsc" -> "数值升序"; "numberDesc" -> "数值降序"; "textAsc" -> "文字升序"; "textDesc" -> "文字降序"; else -> text("mode")
    }, input("json"), output())
    "system.info" -> parts(variable("sectionVariable") ?: enum("section"), output())
    "system.keepawake" -> parts(variable("enabledVariable")?.let { "亮屏开关=$it" } ?: when (boolean("enabled")) {
        true -> "开启"; false -> "关闭"; null -> "未设置"
    }, output())
    "date.adddays" -> parts(input("timestampMs"), "增加 ${input("days")} 天", output())
    "date.daysbetween" -> parts("${input("startMs")} 至 ${input("endMs")}", output())
    "date.format" -> parts(input("timestampMs"), input("format", quoted = true), "时区偏移 ${input("offsetMinutes")} 分钟", output())
    else -> textDetails(kind) ?: fileDetails(kind) ?: dataDetails(kind)
}

private fun ProgramLabelArguments.textDetails(kind: String): String? {
    if (!kind.startsWith("text.")) return null
    val operation = when (kind) {
        "text.sub", "text.remove" -> "起始 ${input("start")}，长度 ${input("length")}"
        "text.left", "text.right" -> "长度 ${input("length")}"
        "text.insert" -> "位置 ${input("start")} 插入 ${input("value", quoted = true)}"
        "text.replace", "text.replacefirst" -> "${input("needle", quoted = true)} → ${input("replacement", quoted = true)}"
        "text.regexreplace" -> "规则 ${input("pattern", quoted = true)} → ${input("replacement", quoted = true)}"
        "text.between", "text.betweennth" -> parts("${input("left", quoted = true)} 与 ${input("right", quoted = true)} 之间",
            if (kind == "text.betweennth") "第 ${input("occurrence")} 段" else null)
        "text.find", "text.findnth" -> parts("查找 ${input("needle", quoted = true)}",
            if (kind == "text.findnth") "第 ${input("occurrence")} 次" else "起始 ${input("start")}")
        "text.after", "text.before", "text.contains", "text.count", "text.startswith", "text.endswith" -> "目标 ${input("needle", quoted = true)}"
        "text.regexall", "text.regexextract", "text.regexmatch" -> "规则 ${input("pattern", quoted = true)}"
        "text.numberat" -> "第 ${input("index")} 个数字（带正负号）"
        "text.repeat" -> "${input("count")} 次"
        "text.split", "text.join" -> "分隔符 ${input("separator", quoted = true)}"
        else -> null
    }
    return parts(input(if (kind == "text.join") "json" else "text", quoted = true), operation, output())
}

private fun ProgramLabelArguments.fileDetails(kind: String): String? {
    if (!kind.startsWith("files.")) return null
    val operation = when (kind) {
        "files.copy", "files.move", "files.unzip", "files.zip" -> "到 ${input("destination")}"
        "files.readline", "files.deleteline" -> "第 ${input("line")} 行"
        "files.insertline", "files.writeline" -> parts("第 ${input("line")} 行", input("text", quoted = true))
        "files.write", "files.append" -> input("text", quoted = true)
        "files.kvget", "files.kvremove" -> "键 ${input("key", quoted = true)}"
        "files.kvset" -> "键 ${input("key", quoted = true)} = ${input("valueJson")}"
        "files.kvadd" -> "键 ${input("key", quoted = true)} 累加 ${input("delta")}"
        "files.frombase64" -> variable("base64Variable")?.let { "Base64来源 $it" } ?: "固定Base64内容"
        else -> null
    }
    return parts(input("path"), operation, output())
}

private fun ProgramLabelArguments.dataDetails(kind: String): String? {
    if (!kind.startsWith("data.")) return null
    val operation = when (kind) {
        "data.get", "data.getjson", "data.has", "data.remove" -> "键/索引 ${input("key", quoted = true)}"
        "data.set" -> "键/索引 ${input("key", quoted = true)} = ${input("valueJson")}"
        "data.append" -> "追加 ${input("valueJson")}"
        else -> null
    }
    return parts(input(if (kind == "data.text") "valueJson" else "json"), operation, output())
}

private fun ProgramLabelArguments.filterMode(): String? {
    variable("modeVariable")?.let { return it }
    val mode = text("mode") ?: return null
    val prefix = when { mode.startsWith("keep") -> "保留"; mode.startsWith("remove") -> "去掉"; else -> return mode }
    val suffix = when (mode.removePrefix("keep").removePrefix("remove")) {
        "Digits" -> "数字"; "Letters" -> "英文字母"; "Alnum" -> "英文数字"; "Han" -> "汉字"; "Whitespace" -> "空白"; else -> return mode
    }
    return prefix + suffix
}
