package com.autoscript.studio

// Display labels only; persisted values and runtime validation use fixed contract names.
internal val pureUtilityChoices: Map<String, List<Pair<String, String>>> = mapOf(
    "system.info:section" to listOf("device" to "设备型号与系统", "display" to "屏幕方向与密度", "battery" to "电池", "network" to "网络状态", "storage" to "私有存储所在卷", "permissions" to "系统授权与输入能力"),
"network.request:method" to listOf("GET" to "GET", "POST" to "POST", "PUT" to "PUT", "OPTIONS" to "OPTIONS", "DELETE" to "DELETE", "HEAD" to "HEAD"),
    "text.urlencode:mode" to listOf("component" to "URL组成部分", "form" to "表单（空格用+）"),
    "text.urldecode:mode" to listOf("component" to "URL组成部分", "form" to "表单（空格用+）"),
    "math.unary:operation" to listOf("abs" to "绝对值", "ceil" to "向上取整", "floor" to "向下取整", "sqrt" to "平方根", "ln" to "自然对数", "log10" to "常用对数", "exp" to "指数", "sin" to "正弦", "cos" to "余弦", "tan" to "正切", "asin" to "反正弦", "acos" to "反余弦", "atan" to "反正切", "deg" to "弧度转角度", "rad" to "角度转弧度"),
    "math.binary:operation" to listOf("min" to "较小值", "max" to "较大值", "pow" to "乘方", "atan2" to "双参数反正切", "log" to "指定底数对数"),
    "text.filter:mode" to listOf("keepDigits" to "仅留数字", "removeDigits" to "去掉数字", "keepLetters" to "仅留英文字母", "removeLetters" to "去掉英文字母", "keepAlnum" to "仅留英文和数字", "removeAlnum" to "去掉英文和数字", "keepHan" to "仅留汉字", "removeHan" to "去掉汉字", "keepWhitespace" to "仅留空白", "removeWhitespace" to "去掉空白"),
    "data.encode:mode" to listOf("text" to "文本→JSON字符串", "json" to "校验并整理JSON"),
    "data.sort:mode" to listOf("numberAsc" to "数字升序", "numberDesc" to "数字降序", "textAsc" to "文本升序", "textDesc" to "文本降序"),
)
