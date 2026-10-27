package com.autoscript.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autoscript.core.designsystem.AutoScriptPalette
import com.autoscript.project.store.ProjectSnapshot
import com.autoscript.project.store.ProjectVariableType
import com.autoscript.studio.generated.BlockContract
import com.autoscript.studio.generated.BlockPropertyEditor
import com.google.gson.JsonObject

internal val utilityApiKinds = mapOf(
    "Text.numberAt" to "text.numberat",
    "Input.touchDown" to "input.touchdown",
    "Input.touchMove" to "input.touchmove",
    "Input.touchUp" to "input.touchup",
    "Floating.image" to "floating.image",
    "Floating.waitImageEvent" to "floating.waitevent",
    "Floating.closeImage" to "floating.closeimage",
    "Input.imeStatus" to "input.imestatus",
    "Input.imeText" to "input.imetext",
    "Files.modifiedTime" to "files.modifiedtime",
    "System.info" to "system.info",
    "System.keepScreenAwake" to "system.keepawake",
    "Apps.openUrl" to "app.openurl",
    "Network.request" to "network.request",
    "Text.urlEncode" to "text.urlencode",
    "Text.urlDecode" to "text.urldecode",
    "Text.toHex" to "text.tohex",
    "Text.fromHex" to "text.fromhex",
    "Text.toBase64" to "text.tobase64",
    "Text.fromBase64" to "text.frombase64",
    "Text.findNth" to "text.findnth",
    "Text.betweenNth" to "text.betweennth",
    "Path.name" to "path.name",
    "Path.parent" to "path.parent",
    "Path.normalize" to "path.normalize",
    "Path.join" to "path.join",
    "Math.unary" to "math.unary",
    "Math.binary" to "math.binary",
    "Math.roundTo" to "math.roundto",
    "Text.formatNumber" to "text.formatnumber",
    "Text.regexReplace" to "text.regexreplace",
    "Text.filter" to "text.filter",
    "Text.reverse" to "text.reverse",
    "Data.encode" to "data.encode",
    "Data.sort" to "data.sort",
    "Files.zip" to "files.zip",
    "Files.unzip" to "files.unzip",
    "Files.getSize" to "files.getsize",
    "Files.getExtension" to "files.getextension",
    "Files.directoryStats" to "files.directorystats",
    "Files.toBase64" to "files.tobase64",
    "Files.fromBase64" to "files.frombase64",
    "Files.append" to "files.append",
    "Files.exists" to "files.exists",
    "Files.isDir" to "files.isdir",
    "Files.mkdir" to "files.mkdir",
    "Files.copy" to "files.copy",
    "Files.move" to "files.move",
    "Files.rmdir" to "files.rmdir",
    "Files.readLine" to "files.readline",
    "Files.insertLine" to "files.insertline",
    "Files.deleteLine" to "files.deleteline",
    "Files.lineCount" to "files.linecount",
    "Files.writeLine" to "files.writeline",
    "Files.kvSet" to "files.kvset",
    "Files.kvGet" to "files.kvget",
    "Files.kvRemove" to "files.kvremove",
    "Files.kvKeys" to "files.kvkeys",
    "Files.kvAdd" to "files.kvadd",
    "Text.length" to "text.length",
    "Text.contains" to "text.contains",
    "Text.startsWith" to "text.startswith",
    "Text.endsWith" to "text.endswith",
    "Text.count" to "text.count",
    "Text.split" to "text.split",
    "Text.join" to "text.join",
    "Text.left" to "text.left",
    "Text.right" to "text.right",
    "Text.sub" to "text.sub",
    "Text.insert" to "text.insert",
    "Text.remove" to "text.remove",
    "Text.replace" to "text.replace",
    "Text.replaceFirst" to "text.replacefirst",
    "Text.repeatText" to "text.repeat",
    "Text.between" to "text.between",
    "Text.before" to "text.before",
    "Text.after" to "text.after",
    "Text.trim" to "text.trim",
    "Text.find" to "text.find",
    "Text.lower" to "text.lower",
    "Text.upper" to "text.upper",
    "Text.regexMatch" to "text.regexmatch",
    "Text.regexExtract" to "text.regexextract",
    "Text.regexAll" to "text.regexall",
    "Data.valid" to "data.valid",
    "Data.type" to "data.type",
    "Data.has" to "data.has",
    "Data.getJson" to "data.getjson",
    "Date.format" to "date.format",
    "Date.addDays" to "date.adddays",
    "Date.daysBetween" to "date.daysbetween",
    "Math.randInt" to "math.randint",
    "Math.randStr" to "math.randstr",
    "Math.round" to "math.round",
    "Math.clamp" to "math.clamp",
    "Math.inRange" to "math.inrange",
    "Math.angle" to "math.angle",
    "Math.flipAngle" to "math.flipangle",
    "Math.joystickX" to "math.joystickx",
    "Math.joystickY" to "math.joysticky",
    "Math.red" to "math.red",
    "Math.green" to "math.green",
    "Math.blue" to "math.blue",
    "Math.distance" to "math.distance",
    "System.elapsedRealtimeMillis" to "system.elapsed",
    "System.getTimeMillis" to "system.time",
    "System.getScreenSize" to "system.screensize",
)
internal val visualUtilityKinds = utilityApiKinds.values.toSet()
internal fun utilityResultType(kind: String): ProjectVariableType = when(kind) {
    "text.numberat" -> ProjectVariableType.STRING
    "input.touchdown", "input.touchmove", "input.touchup" -> ProjectVariableType.INTEGER
    "floating.image", "floating.closeimage" -> ProjectVariableType.INTEGER
    "floating.waitevent" -> ProjectVariableType.STRING
    "input.imestatus" -> ProjectVariableType.STRING
    "input.imetext" -> ProjectVariableType.INTEGER
    "files.modifiedtime" -> ProjectVariableType.INTEGER
    "system.info" -> ProjectVariableType.STRING
    "system.keepawake" -> ProjectVariableType.INTEGER
    "app.openurl" -> ProjectVariableType.INTEGER
    "network.request" -> ProjectVariableType.STRING
    "text.urlencode" -> ProjectVariableType.STRING
    "text.urldecode" -> ProjectVariableType.STRING
    "text.tohex" -> ProjectVariableType.STRING
    "text.fromhex" -> ProjectVariableType.STRING
    "text.tobase64" -> ProjectVariableType.STRING
    "text.frombase64" -> ProjectVariableType.STRING
    "text.findnth" -> ProjectVariableType.INTEGER
    "text.betweennth" -> ProjectVariableType.STRING
    "path.name" -> ProjectVariableType.STRING
    "path.parent" -> ProjectVariableType.STRING
    "path.normalize" -> ProjectVariableType.STRING
    "path.join" -> ProjectVariableType.STRING
    "text.formatnumber" -> ProjectVariableType.STRING
    "text.regexreplace" -> ProjectVariableType.STRING
    "text.filter" -> ProjectVariableType.STRING
    "text.reverse" -> ProjectVariableType.STRING
    "data.encode" -> ProjectVariableType.STRING
    "data.sort" -> ProjectVariableType.STRING
    "files.zip", "files.unzip" -> ProjectVariableType.INTEGER
    "files.getsize", "files.frombase64" -> ProjectVariableType.INTEGER
    "files.getextension", "files.directorystats", "files.tobase64" -> ProjectVariableType.STRING
    "files.append", "files.exists", "files.isdir", "files.mkdir", "files.copy", "files.move", "files.rmdir", "files.insertline", "files.deleteline", "files.linecount", "files.writeline", "files.kvset", "files.kvremove", "files.kvadd" -> ProjectVariableType.INTEGER
    "files.readline", "files.kvget", "files.kvkeys" -> ProjectVariableType.STRING
    "text.length", "text.contains", "text.startswith", "text.endswith", "text.count", "text.find", "text.regexmatch", "data.valid", "data.has", "date.adddays" -> ProjectVariableType.INTEGER
    "date.daysbetween" -> ProjectVariableType.NUMBER
    "text.split", "text.join", "text.left", "text.right", "text.sub", "text.insert", "text.remove", "text.replace", "text.replacefirst", "text.repeat", "text.between", "text.before", "text.after", "text.trim", "text.lower", "text.upper", "text.regexextract", "text.regexall", "data.type", "data.getjson", "date.format" -> ProjectVariableType.STRING
    "math.randstr" -> ProjectVariableType.STRING
    "math.randint", "math.round", "math.inrange", "math.red", "math.green", "math.blue", "system.elapsed", "system.time", "system.screensize" -> ProjectVariableType.INTEGER
    else -> ProjectVariableType.NUMBER
}
private val utilityBounds = mapOf(
    "text.numberat:text" to (0.0 to 4096.0),
    "text.numberat:index" to (1.0 to 64.0),
    "input.touchdown:pointerId" to (0.0 to 4.0),"input.touchmove:pointerId" to (0.0 to 4.0),"input.touchup:pointerId" to (0.0 to 4.0),
    "input.touchdown:x" to (0.0 to 32767.0),"input.touchdown:y" to (0.0 to 32767.0),
    "input.touchmove:x" to (0.0 to 32767.0),"input.touchmove:y" to (0.0 to 32767.0),
    "floating.image:name" to (1.0 to 32.0),
    "floating.image:imagePath" to (1.0 to 256.0),
    "floating.image:x" to (-1.0 to 8192.0),
    "floating.image:y" to (-1.0 to 8192.0),
    "floating.image:width" to (1.0 to 2048.0),
    "floating.image:height" to (1.0 to 2048.0),
    "floating.image:draggable" to (0.0 to 1.0),
    "floating.waitevent:name" to (1.0 to 32.0),
    "floating.waitevent:timeoutMs" to (0.0 to 60000.0),
    "floating.closeimage:name" to (1.0 to 32.0),
    "input.imetext:text" to (0.0 to 4096.0),
    "files.modifiedtime:path" to (1.0 to 256.0),
    "system.info:section" to (1.0 to 16.0),
    "system.keepawake:enabled" to (0.0 to 1.0),
    "app.openurl:url" to (1.0 to 2048.0),
    "network.request:method" to (1.0 to 8.0),
    "network.request:url" to (1.0 to 2048.0),
    "network.request:headersJson" to (2.0 to 4096.0),
    "network.request:body" to (0.0 to 8192.0),
    "network.request:timeoutMs" to (100.0 to 30000.0),
    "network.request:maxResponseBytes" to (1.0 to 16384.0),
    "text.urlencode:text" to (0.0 to 65536.0),
    "text.urlencode:mode" to (1.0 to 16.0),
    "text.urldecode:text" to (0.0 to 65536.0),
    "text.urldecode:mode" to (1.0 to 16.0),
    "text.tohex:text" to (0.0 to 65536.0),
    "text.fromhex:text" to (0.0 to 65536.0),
    "text.tobase64:text" to (0.0 to 65536.0),
    "text.frombase64:text" to (0.0 to 65536.0),
    "text.findnth:text" to (0.0 to 65536.0),
    "text.findnth:needle" to (1.0 to 4096.0),
    "text.findnth:occurrence" to (1.0 to 1024.0),
    "text.betweennth:text" to (0.0 to 65536.0),
    "text.betweennth:left" to (1.0 to 4096.0),
    "text.betweennth:right" to (1.0 to 4096.0),
    "text.betweennth:occurrence" to (1.0 to 1024.0),
    "path.name:path" to (0.0 to 256.0),
    "path.parent:path" to (0.0 to 256.0),
    "path.normalize:path" to (0.0 to 256.0),
    "path.join:left" to (0.0 to 256.0),
    "path.join:right" to (0.0 to 256.0),
    "math.unary:operation" to (0.0 to 16.0),
    "math.unary:value" to (-1000000000000.0 to 1000000000000.0),
    "math.binary:operation" to (0.0 to 16.0),
    "math.binary:left" to (-1000000000000.0 to 1000000000000.0),
    "math.binary:right" to (-1000000000000.0 to 1000000000000.0),
    "math.roundto:value" to (-1000000000000.0 to 1000000000000.0),
    "math.roundto:digits" to (0.0 to 9.0),
    "text.formatnumber:value" to (-1000000000000.0 to 1000000000000.0),
    "text.formatnumber:digits" to (0.0 to 9.0),
    "text.formatnumber:minIntegerDigits" to (1.0 to 32.0),
    "text.regexreplace:text" to (0.0 to 4096.0),
    "text.regexreplace:pattern" to (1.0 to 128.0),
    "text.regexreplace:replacement" to (0.0 to 4096.0),
    "text.filter:text" to (0.0 to 65536.0),
    "text.filter:mode" to (0.0 to 24.0),
    "text.reverse:text" to (0.0 to 65536.0),
    "data.encode:value" to (0.0 to 65536.0),
    "data.encode:mode" to (0.0 to 8.0),
    "data.sort:json" to (0.0 to 65536.0),
    "data.sort:mode" to (0.0 to 16.0),
    "files.zip:path" to (1.0 to 256.0),
    "files.zip:destination" to (1.0 to 256.0),
    "files.unzip:path" to (1.0 to 256.0),
    "files.unzip:destination" to (1.0 to 256.0),
    "files.getsize:path" to (1.0 to 256.0),
    "files.getextension:path" to (1.0 to 256.0),
    "files.directorystats:path" to (1.0 to 256.0),
    "files.tobase64:path" to (1.0 to 256.0),
    "files.frombase64:path" to (1.0 to 256.0),
    "files.frombase64:base64" to (0.0 to 65536.0),
    "files.append:path" to (1.0 to 256.0),
    "files.append:text" to (0.0 to 65536.0),
    "files.exists:path" to (1.0 to 256.0),
    "files.isdir:path" to (1.0 to 256.0),
    "files.mkdir:path" to (1.0 to 256.0),
    "files.copy:path" to (1.0 to 256.0),
    "files.copy:destination" to (1.0 to 256.0),
    "files.move:path" to (1.0 to 256.0),
    "files.move:destination" to (1.0 to 256.0),
    "files.rmdir:path" to (1.0 to 256.0),
    "files.readline:path" to (1.0 to 256.0),
    "files.readline:line" to (1.0 to 65537.0),
    "files.insertline:path" to (1.0 to 256.0),
    "files.insertline:line" to (1.0 to 65537.0),
    "files.insertline:text" to (0.0 to 65536.0),
    "files.deleteline:path" to (1.0 to 256.0),
    "files.deleteline:line" to (1.0 to 65537.0),
    "files.linecount:path" to (1.0 to 256.0),
    "files.writeline:path" to (1.0 to 256.0),
    "files.writeline:line" to (1.0 to 65537.0),
    "files.writeline:text" to (0.0 to 65536.0),
    "files.kvset:path" to (1.0 to 256.0),
    "files.kvset:key" to (1.0 to 128.0),
    "files.kvset:valueJson" to (0.0 to 65536.0),
    "files.kvget:path" to (1.0 to 256.0),
    "files.kvget:key" to (1.0 to 128.0),
    "files.kvremove:path" to (1.0 to 256.0),
    "files.kvremove:key" to (1.0 to 128.0),
    "files.kvkeys:path" to (1.0 to 256.0),
    "files.kvadd:path" to (1.0 to 256.0),
    "files.kvadd:key" to (1.0 to 128.0),
    "files.kvadd:delta" to (-9007199254740991.0 to 9007199254740991.0),
    "text.length:text" to (0.0 to 65536.0),
    "text.contains:text" to (0.0 to 65536.0),
    "text.contains:needle" to (0.0 to 4096.0),
    "text.startswith:text" to (0.0 to 65536.0),
    "text.startswith:needle" to (0.0 to 4096.0),
    "text.endswith:text" to (0.0 to 65536.0),
    "text.endswith:needle" to (0.0 to 4096.0),
    "text.count:text" to (0.0 to 65536.0),
    "text.count:needle" to (0.0 to 4096.0),
    "text.split:text" to (0.0 to 65536.0),
    "text.split:separator" to (0.0 to 4096.0),
    "text.join:json" to (0.0 to 65536.0),
    "text.join:separator" to (0.0 to 4096.0),
    "text.left:text" to (0.0 to 65536.0),
    "text.left:length" to (0.0 to 65536.0),
    "text.right:text" to (0.0 to 65536.0),
    "text.right:length" to (0.0 to 65536.0),
    "text.sub:text" to (0.0 to 65536.0),
    "text.sub:start" to (1.0 to 65537.0),
    "text.sub:length" to (0.0 to 65536.0),
    "text.insert:text" to (0.0 to 65536.0),
    "text.insert:start" to (1.0 to 65537.0),
    "text.insert:value" to (0.0 to 65536.0),
    "text.remove:text" to (0.0 to 65536.0),
    "text.remove:start" to (1.0 to 65537.0),
    "text.remove:length" to (0.0 to 65536.0),
    "text.replace:text" to (0.0 to 65536.0),
    "text.replace:needle" to (0.0 to 4096.0),
    "text.replace:replacement" to (0.0 to 65536.0),
    "text.replacefirst:text" to (0.0 to 65536.0),
    "text.replacefirst:needle" to (0.0 to 4096.0),
    "text.replacefirst:replacement" to (0.0 to 65536.0),
    "text.repeat:text" to (0.0 to 65536.0),
    "text.repeat:count" to (0.0 to 1024.0),
    "text.between:text" to (0.0 to 65536.0),
    "text.between:left" to (1.0 to 4096.0),
    "text.between:right" to (1.0 to 4096.0),
    "text.before:text" to (0.0 to 65536.0),
    "text.before:needle" to (0.0 to 4096.0),
    "text.after:text" to (0.0 to 65536.0),
    "text.after:needle" to (0.0 to 4096.0),
    "text.trim:text" to (0.0 to 65536.0),
    "text.find:text" to (0.0 to 65536.0),
    "text.find:needle" to (0.0 to 4096.0),
    "text.find:start" to (1.0 to 65537.0),
    "text.lower:text" to (0.0 to 65536.0),
    "text.upper:text" to (0.0 to 65536.0),
    "text.regexmatch:text" to (0.0 to 4096.0),
    "text.regexmatch:pattern" to (1.0 to 128.0),
    "text.regexextract:text" to (0.0 to 4096.0),
    "text.regexextract:pattern" to (1.0 to 128.0),
    "text.regexall:text" to (0.0 to 4096.0),
    "text.regexall:pattern" to (1.0 to 128.0),
    "data.valid:json" to (0.0 to 65536.0),
    "data.type:json" to (0.0 to 65536.0),
    "data.has:json" to (0.0 to 65536.0),
    "data.has:key" to (1.0 to 128.0),
    "data.getjson:json" to (0.0 to 65536.0),
    "data.getjson:key" to (1.0 to 128.0),
    "date.format:timestampMs" to (0.0 to 253402300799999.0),
    "date.format:offsetMinutes" to (-840.0 to 840.0),
    "date.format:format" to (1.0 to 16.0),
    "date.adddays:timestampMs" to (0.0 to 253402300799999.0),
    "date.adddays:days" to (-3652058.0 to 3652058.0),
    "date.daysbetween:startMs" to (0.0 to 253402300799999.0),
    "date.daysbetween:endMs" to (0.0 to 253402300799999.0),
    "math.randint:min" to (-9007199254740991.0 to 9007199254740991.0),
    "math.randint:max" to (-9007199254740991.0 to 9007199254740991.0),
    "math.randstr:length" to (1.0 to 1024.0),
    "math.randstr:mode" to (1.0 to 5.0),
    "math.round:value" to (-1000000000000.0 to 1000000000000.0),
    "math.clamp:value" to (-1000000000000.0 to 1000000000000.0),
    "math.clamp:min" to (-1000000000000.0 to 1000000000000.0),
    "math.clamp:max" to (-1000000000000.0 to 1000000000000.0),
    "math.inrange:value" to (-1000000000000.0 to 1000000000000.0),
    "math.inrange:min" to (-1000000000000.0 to 1000000000000.0),
    "math.inrange:max" to (-1000000000000.0 to 1000000000000.0),
    "math.angle:x1" to (-1000000000000.0 to 1000000000000.0),
    "math.angle:y1" to (-1000000000000.0 to 1000000000000.0),
    "math.angle:x2" to (-1000000000000.0 to 1000000000000.0),
    "math.angle:y2" to (-1000000000000.0 to 1000000000000.0),
    "math.flipangle:angle" to (-180.0 to 180.0),
    "math.flipangle:mode" to (1.0 to 2.0),
    "math.joystickx:centerX" to (-1000000000000.0 to 1000000000000.0),
    "math.joystickx:angle" to (-180.0 to 180.0),
    "math.joystickx:radius" to (0.0 to 1000000000.0),
    "math.joysticky:centerY" to (-1000000000000.0 to 1000000000000.0),
    "math.joysticky:angle" to (-180.0 to 180.0),
    "math.joysticky:radius" to (0.0 to 1000000000.0),
    "math.red:rgb" to (0.0 to 16777215.0),
    "math.green:rgb" to (0.0 to 16777215.0),
    "math.blue:rgb" to (0.0 to 16777215.0),
    "math.distance:x1" to (-1000000000000.0 to 1000000000000.0),
    "math.distance:y1" to (-1000000000000.0 to 1000000000000.0),
    "math.distance:x2" to (-1000000000000.0 to 1000000000000.0),
    "math.distance:y2" to (-1000000000000.0 to 1000000000000.0),
)
internal fun utilityArgumentError(contract: BlockContract, inputs: Map<String,String>, variables: List<CalculationVariable>): String? {
    fun type(name: String) = variables.firstOrNull { it.name == name }?.type
    val outputPaths = if(contract.kind=="system.screensize") listOf("widthVariable","heightVariable") else listOf("resultVariable")
    for(path in outputPaths) if(type(inputs[path].orEmpty())!=utilityResultType(contract.kind)) return "请选择已声明且类型匹配的结果变量"
    if(outputPaths.size==2 && inputs[outputPaths[0]]==inputs[outputPaths[1]]) return "宽度和高度需选择不同变量"
    val fields=utilityInputFields(contract)
    for(field in fields) {
        val source=inputs[field.path+"Variable"].orEmpty()
        if(source.isNotEmpty()) {
            val valid=utilitySourceMatches(field.editor,type(source))
            if(!valid) return "来源变量类型不匹配：${field.label}"
        }
        val text=inputs[field.path].orEmpty()
        pureUtilityChoices[contract.kind+":"+field.path]?.let { options ->
            if (options.none { it.first==text }) return "请选择有效的运算方式：${field.label}"
        }
        val bounds=utilityBounds[contract.kind+":"+field.path] ?: return "未知工具参数"
        if(field.editor==BlockPropertyEditor.STRING) {
            val length=text.toByteArray(Charsets.UTF_8).size
            if(length<bounds.first||length>bounds.second) return "文本超出字节范围：${field.label}"
            if(field.path=="format" && text !in setOf("date","time","datetime")) return "日期格式请选择 date / time / datetime"
            if(contract.kind.startsWith("files.") && field.path in setOf("path","destination") && source.isEmpty() &&
                (text.split('/').size>16 || text.split('/').any { it.isEmpty()||it.startsWith('.')||it.endsWith('.')||it.endsWith(' ')||it.any { c -> c.isISOControl() || c in "\\:*?\"<>|" } })) return "路径需为项目 data 内相对路径"
            if(contract.kind in setOf("files.insertline","files.writeline") && field.path=="text" && source.isEmpty() && ('\r' in text || '\n' in text)) return "行文本不能包含换行"
            continue
        }
        val value=text.toDoubleOrNull()
        if(value==null||!value.isFinite()||value<bounds.first||value>bounds.second||
            (field.editor==BlockPropertyEditor.INTEGER&&text.toLongOrNull()==null)) return "固定值无效或超出范围：${field.label}"
    }
    if(inputs["minVariable"].isNullOrEmpty()&&inputs["maxVariable"].isNullOrEmpty()&&inputs.containsKey("min")&&inputs.containsKey("max")) {
        if(requireNotNull(inputs["min"]?.toDoubleOrNull())>requireNotNull(inputs["max"]?.toDoubleOrNull())) return "下限不能大于上限"
    }
    if(contract.kind=="math.angle"&&fields.all { inputs[it.path+"Variable"].isNullOrEmpty() }&&
        inputs["x1"]?.toDoubleOrNull()==inputs["x2"]?.toDoubleOrNull()&&inputs["y1"]?.toDoubleOrNull()==inputs["y2"]?.toDoubleOrNull()) return "相同点没有方向"
    return null
}
private fun utilityInputFields(contract: BlockContract) = contract.properties.filter {
    it.path !in setOf("resultVariable","widthVariable","heightVariable") && !it.path.endsWith("Variable")
}
private fun utilitySourceMatches(editor: BlockPropertyEditor, actual: ProjectVariableType?): Boolean = when(editor) {
    BlockPropertyEditor.STRING -> actual==ProjectVariableType.STRING
    BlockPropertyEditor.INTEGER -> actual==ProjectVariableType.INTEGER
    BlockPropertyEditor.NUMBER -> actual in setOf(ProjectVariableType.INTEGER,ProjectVariableType.NUMBER)
    else -> false
}
@Composable
internal fun VisualUtilityArguments(contract: BlockContract, arguments: JsonObject, project: ProjectSnapshot, flowId: String,
    onManageVariables: (() -> Unit)?, onDismiss: () -> Unit, onConfirm: (JsonObject) -> Unit, confirmLabel: String, visible: Boolean = true) {
    val initial=remember(contract.kind,arguments) { blockPropertyTexts(contract,arguments) }
    var inputs by remember(contract.kind,arguments) { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    val variables=calculationVariables(project.manifest.variables,flowId,project.manifest.flows)
    fun update(key: String,text: String) { inputs=inputs+(key to text); error=null }
    fun close() { if(inputs!=initial) discard=true else onDismiss() }
    val outputs=if(contract.kind=="system.screensize") listOf("widthVariable","heightVariable") else listOf("resultVariable")
    if (visible) StudioPanelDialog(contract.title,::close,confirmLabel,onConfirm={
        error=utilityArgumentError(contract,inputs,variables)
        if(error==null) {
            val parsed=parseBlockArguments(contract,inputs,null,emptyMap(),project.manifest.resources)
            error=parsed.second
            parsed.first?.let(onConfirm)
        }
    }) {
        Column(Modifier.fillMaxWidth().heightIn(max=360.dp).verticalScroll(rememberScrollState()).padding(10.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Text(contract.summary,fontSize=11.sp,color=AutoScriptPalette.TextSecondary)
            if (contract.kind in textRuleKinds) {
                Text(textRegexResultHint(contract.kind),
                    fontSize = 11.sp, color = AutoScriptPalette.TextSecondary)
                val selectedRule = if (inputs["patternVariable"].isNullOrEmpty())
                    textExtractionRules.firstOrNull { it.pattern == inputs["pattern"] }?.label ?: "自定义正则" else "变量规则"
                DesignerChoice("常用规则", selectedRule,
                    textExtractionRules.map { it.id to it.label } + ("custom" to "自定义正则"), true) {
                    inputs = applyTextExtractionRule(inputs, it); error = null
                }
                Text("正则按原样填写，例如 [0-9]+、\\d+、\\p{Han}+；支持分组和重复，不支持环视或反向引用。",
                    fontSize = 10.sp, color = AutoScriptPalette.TextSecondary)
            }
            utilityInputFields(contract).forEach { field ->
                val path=field.path+"Variable"
                val source=inputs[path].orEmpty()
                val choices=variables.filter { utilitySourceMatches(field.editor,it.type) }
                if(source.isEmpty()) {
                    val fixedChoices=pureUtilityChoices[contract.kind+":"+field.path]
                    if(fixedChoices!=null) {
                        DesignerChoice(field.label,fixedChoices.firstOrNull { it.first==inputs[field.path] }?.second ?: "请选择",fixedChoices,true) { update(field.path,it) }
                    } else if(field.path=="format") {
                        DesignerChoice(field.label,inputs[field.path].orEmpty(),listOf("date" to "日期","time" to "时间","datetime" to "日期+时间"),true) { update(field.path,it) }
                    } else if(field.path=="mode") {
                        val options=if(contract.kind=="math.randstr") listOf("1" to "小写","2" to "大写","3" to "大小写","4" to "大小写+数字","5" to "小写+数字") else listOf("1" to "上下翻转","2" to "左右翻转")
                        DesignerChoice(field.label,options.find { it.first==inputs[field.path] }?.second ?: "请选择",options,true) { update(field.path,it) }
                    } else DesignerInput(field.label,inputs[field.path].orEmpty(),true,Modifier.fillMaxWidth(),numeric=field.editor!=BlockPropertyEditor.STRING) { update(field.path,it) }
                }
                DesignerChoice(if(source.isEmpty()) "来源" else field.label,source.ifEmpty { "固定值" },
                    listOf("" to "固定值")+choices.map { it.name to it.name },true) { update(path,it) }
            }
            val expected=utilityResultType(contract.kind)
            val choices=variables.filter { it.type==expected }.map { it.name to it.name }
            outputs.forEach { key ->
                val label=contract.properties.first { it.path==key }.label
                DesignerChoice(label,inputs[key].orEmpty().ifEmpty { "请选择" },choices,true) { update(key,it) }
            }
            onManageVariables?.let { TextButton(onClick=it,contentPadding=PaddingValues(horizontal=5.dp,vertical=0.dp)) { Text("维护变量",fontSize=11.sp) } }
            error?.let { Text(it,color=AutoScriptPalette.Accent,fontSize=11.sp) }
        }
    }
    if(visible && discard) StudioConfirmDialog(onDismissRequest={discard=false},title={Text("放弃未保存参数？")},
        text={Text("只关闭配置，不加入或保存积木。")},actions=listOf(
            StudioConfirmAction("继续编辑",{discard=false}),
            StudioConfirmAction("放弃",{discard=false;onDismiss()},destructive=true)))
}

