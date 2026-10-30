package com.autoscript.studio

import android.app.Instrumentation
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.autoscript.core.designsystem.AutoScriptTheme
import com.autoscript.project.store.*
import com.autoscript.runtime.client.RuntimeClient
import com.google.gson.JsonObject

/** Public deterministic examples, executed through the real visual compiler and Rust runtime. */
internal object LibraryUsageAcceptanceFixture {
    private data class Example(val kind: String, val expected: String, val args: List<Pair<String, Any>>)
    private fun example(kind: String, expected: String, vararg args: Pair<String, Any>) = Example(kind, expected, args.toList())
    fun run(stage: String, test: Instrumentation, activity: ComponentActivity, client: RuntimeClient, store: ProjectStore,
        initial: ProjectSnapshot, record: (String, () -> Unit) -> Unit): ProjectSnapshot {
        val ui = UsageTestUi(test)
        val rt = OwnedUsageRuntime(client, store, initial)
        var current by mutableStateOf<String?>(null)
        var values by mutableStateOf(JsonObject())
        var accepted by mutableStateOf<JsonObject?>(null)
        var library by mutableStateOf(stage == "libraryUI")
        var chosen by mutableStateOf<String?>(null)
        val groups = FunctionCatalog.visualGroups()
        test.runOnMainSync { activity.setContent { AutoScriptTheme {
            if (library) FunctionLibraryDialog(groups, BlockCatalog.insertable.flatMap { it.requiredCapabilities }.toSet(),
                { library = false }, { chosen = FunctionCatalog.blockKindOf(it) })
            current?.let { kind -> VisualUtilityArguments(requireNotNull(BlockCatalog.find(kind)), values,
                rt.snapshot, rt.main.flowId, null, { current = null }, { accepted = it; current = null }, "QA加入") }
        } } }
        try {
            if (stage == "libraryUI") {
                check(groups.sumOf { it.entries.size } == 215)
                for (entry in groups.flatMap { it.entries }) record("library-catalog-real-search-insert-${entry.blockKind}") {
                    test.runOnMainSync { chosen = null }
                    ui.replaceFirst(requireNotNull(entry.blockKind))
                    ui.click("加入${entry.title}")
                    ui.await("correct library route") { chosen == entry.blockKind }
                }
                ui.click("关闭函数库"); ui.absent("关闭函数库")
                return rt.snapshot
            }
            if (stage == "libraryPure") {
                rt.variables("outText" to ProjectVariableType.STRING, "outInt" to ProjectVariableType.INTEGER,
                    "outNumber" to ProjectVariableType.NUMBER)
                val examples = listOf(
                    example("text.length", "4", "text" to "智构A1"),
                    example("text.contains", "1", "text" to "智构A1", "needle" to "构A"),
                    example("text.startswith", "1", "text" to "智构A1", "needle" to "智构"),
                    example("text.endswith", "1", "text" to "智构A1", "needle" to "A1"),
                    example("text.count", "2", "text" to "甲乙甲", "needle" to "甲"),
                    example("text.find", "2", "text" to "甲乙甲", "needle" to "乙"),
                    example("text.findnth", "3", "text" to "甲乙甲", "needle" to "甲", "occurrence" to 2),
                    example("text.left", "智构", "text" to "智构A1", "length" to 2),
                    example("text.right", "A1", "text" to "智构A1", "length" to 2),
                    example("text.sub", "构A", "text" to "智构A1", "start" to 2, "length" to 2),
                    example("text.insert", "甲新乙", "text" to "甲乙", "start" to 2, "value" to "新"),
                    example("text.remove", "甲A", "text" to "甲乙A", "start" to 2, "length" to 1),
                    example("text.replace", "x乙x", "text" to "甲乙甲", "needle" to "甲", "replacement" to "x"),
                    example("text.replacefirst", "x乙甲", "text" to "甲乙甲", "needle" to "甲", "replacement" to "x"),
                    example("text.repeat", "智构智构", "text" to "智构", "count" to 2),
                    example("text.before", "甲", "text" to "甲:乙", "needle" to ":"),
                    example("text.after", "乙", "text" to "甲:乙", "needle" to ":"),
                    example("text.between", "甲", "text" to "[甲][乙]", "left" to "[", "right" to "]"),
                    example("text.betweennth", "乙", "text" to "[甲][乙]", "left" to "[", "right" to "]", "occurrence" to 2),
                    example("text.trim", "智构", "text" to "  智构\t"),
                    example("text.lower", "ab12", "text" to "Ab12"), example("text.upper", "AB12", "text" to "Ab12"),
                    example("text.split", "[\"甲\",\"乙\"]", "text" to "甲,乙", "separator" to ","),
                    example("text.join", "甲-乙", "json" to "[\"甲\",\"乙\"]", "separator" to "-"),
                    example("text.regexmatch", "1", "text" to "A12 B34", "pattern" to "[0-9]+"),
                    example("text.regexextract", "12", "text" to "A12 B34", "pattern" to "[0-9]+"),
                    example("text.regexall", "[\"12\",\"34\"]", "text" to "A12 B34", "pattern" to "[0-9]+"),
                    example("text.regexreplace", "A# B#", "text" to "A12 B34", "pattern" to "[0-9]+", "replacement" to "#"),
                    example("text.numberat", "+100", "text" to "100(+100) 180", "index" to 2),
                    example("text.formatnumber", "0012.50", "value" to 12.5, "digits" to 2, "minIntegerDigits" to 4),
                    example("text.filter", "123", "text" to "智构A1B2C3", "mode" to "keepDigits"),
                    example("text.reverse", "1A构智", "text" to "智构A1"),
                    example("text.tohex", "E699BAE69E84", "text" to "智构"),
                    example("text.fromhex", "智构", "text" to "E699BAE69E84"),
                    example("text.tobase64", "5pm65p6E", "text" to "智构"),
                    example("text.frombase64", "智构", "text" to "5pm65p6E"),
                    example("text.urlencode", "%E6%99%BA%E6%9E%84%20A", "text" to "智构 A", "mode" to "component"),
                    example("text.urldecode", "智构 A", "text" to "%E6%99%BA%E6%9E%84%20A", "mode" to "component"),
                    example("data.valid", "1", "json" to "{\"甲\":1}"),
                    example("data.type", "object", "json" to "{\"甲\":1}"),
                    example("data.has", "1", "json" to "{\"甲\":1}", "key" to "甲"),
                    example("data.getjson", "{\"x\":1}", "json" to "{\"child\":{\"x\":1}}", "key" to "child"),
                    example("data.encode", "\"智构\"", "value" to "智构", "mode" to "text"),
                    example("data.sort", "[1,2,3]", "json" to "[3,1,2]", "mode" to "numberAsc"),
                    example("data.get", "42", "json" to "[42,7]", "key" to "1"),
                    example("data.set", "[42,8]", "json" to "[42,7]", "key" to "2", "valueJson" to "8"),
                    example("data.append", "[42,7,8]", "json" to "[42,7]", "valueJson" to "8"),
                    example("data.remove", "[42]", "json" to "[42,7]", "key" to "2"),
                    example("data.keys", "[\"1\",\"2\"]", "json" to "[42,7]"),
                    example("data.length", "2", "json" to "[42,7]"),
                    example("data.text", "智构", "valueJson" to "\"智构\""),
                    example("path.name", "a.png", "path" to "images/a.png"),
                    example("path.parent", "images", "path" to "images/a.png"),
                    example("path.normalize", "images/a.png", "path" to "images/./a.png"),
                    example("path.join", "images/a.png", "left" to "images", "right" to "a.png"),
                    example("math.distance", "5", "x1" to 0, "y1" to 0, "x2" to 3, "y2" to 4),
                    example("math.round", "4", "value" to 3.6),
                    example("math.clamp", "10", "value" to 15, "min" to 0, "max" to 10),
                    example("math.inrange", "1", "value" to 5, "min" to 0, "max" to 10),
                    example("math.angle", "90", "x1" to 0, "y1" to 0, "x2" to 0, "y2" to 1),
                    example("math.flipangle", "-45", "angle" to 45, "mode" to 1),
                    example("math.joystickx", "110", "centerX" to 100, "angle" to 0, "radius" to 10),
                    example("math.joysticky", "110", "centerY" to 100, "angle" to 90, "radius" to 10),
                    example("math.red", "18", "rgb" to 0x123456), example("math.green", "52", "rgb" to 0x123456),
                    example("math.blue", "86", "rgb" to 0x123456),
                    example("math.unary", "3", "operation" to "abs", "value" to -3),
                    example("math.binary", "8", "operation" to "pow", "left" to 2, "right" to 3),
                    example("math.roundto", "3.14", "value" to 3.14159, "digits" to 2),
                    example("date.adddays", "86400000", "timestampMs" to 0, "days" to 1),
                    example("date.daysbetween", "1.5", "startMs" to 0, "endMs" to 129600000),
                    example("date.format", "1970-01-01", "timestampMs" to 0, "offsetMinutes" to 0, "format" to "date")
                )
                for (item in examples) record("library-${item.kind}-real-form-and-runtime") {
                    val type = when (item.kind) {
                        "data.length" -> ProjectVariableType.INTEGER
                        "data.get", "data.set", "data.append", "data.remove", "data.keys", "data.text" -> ProjectVariableType.STRING
                        else -> utilityResultType(item.kind)
                    }
                    val output = when (type) { ProjectVariableType.INTEGER -> "outInt"; ProjectVariableType.STRING -> "outText"; else -> "outNumber" }
                    val node = rt.block(item.kind, *item.args.toTypedArray(), "resultVariable" to output)
                    val args = if (item.kind in visualUtilityKinds) {
                        test.runOnMainSync { values = node.arguments; accepted = null; current = item.kind }
                        ui.reveal("QA加入"); ui.click("QA加入"); ui.await("utility form submitted") { accepted != null && current == null }
                        requireNotNull(accepted)
                    } else node.arguments
                    rt.run(listOf(node.copy(arguments = args))) { actual ->
                        val observed = actual[output]
                        check(if (type == ProjectVariableType.NUMBER) observed?.toDoubleOrNull()?.let { kotlin.math.abs(it - item.expected.toDouble()) < 0.000001 } == true
                            else observed == item.expected) { "QA_LIBRARY_RESULT:${item.kind}:expected=${item.expected}:actual=$observed" }
                    }
                }
                record("library-random-values-stay-in-declared-ranges") {
                    rt.run(listOf(rt.block("math.randint", "min" to 5, "max" to 8, "resultVariable" to "outInt"),
                        rt.block("math.randstr", "length" to 16, "mode" to 4, "resultVariable" to "outText"))) {
                        check(it["outInt"]?.toLongOrNull() in 5L..8L && it["outText"]?.matches(Regex("[A-Za-z0-9]{16}")) == true)
                    }
                }
            } else error("QA_UNKNOWN_LIBRARY_STAGE")
            return store.openProject(rt.id)
        } finally { test.runOnMainSync { current = null; library = false }; rt.stop() }
    }
}
