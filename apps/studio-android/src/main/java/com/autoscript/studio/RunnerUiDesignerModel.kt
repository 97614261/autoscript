package com.autoscript.studio

import com.google.gson.JsonObject
import com.autoscript.script.ui.*
import kotlin.math.roundToInt

internal enum class RunnerUiControlKind(val wireName: String, val label: String, val control: UiControl) {
    TEXT("text", "单行文本", UiControl.INPUT), INTEGER("integer", "整数", UiControl.NUMBER), BOOLEAN("boolean", "复选框", UiControl.CHECKBOX), CHOICE("choice", "下拉选择", UiControl.SELECT),
    LABEL("text", "标签", UiControl.LABEL), BUTTON("text", "按钮", UiControl.BUTTON), TEXTAREA("text", "多行文本", UiControl.TEXTAREA), IMAGE("text", "图片", UiControl.IMAGE),
    RADIO("choice", "单选组", UiControl.RADIO), LIST("choice", "列表", UiControl.LIST), CONTAINER("text", "容器", UiControl.CONTAINER), SLIDER("integer", "滑块", UiControl.SLIDER), PROGRESS("integer", "进度条", UiControl.PROGRESS), NAVIGATION("text", "分页导航", UiControl.NAVIGATION),
    ;
    companion object { fun fromWireName(value: String) = entries.firstOrNull { it.wireName == value } ?: error("未知控件类型") }
}
internal data class RunnerUiFieldDraft(
    val id: String, val label: String, val kind: RunnerUiControlKind, val required: Boolean, val initialText: String,
    val minimum: String = "", val maximum: String = "", val optionsText: String = "",
    val ui: UiPresentation = UiPresentation(control = kind.control.name),
)
internal data class RunnerUiDesignerDraft(val description: String = "", val fields: List<RunnerUiFieldDraft> = emptyList(), val pages: List<UiPage> = listOf(UiPage())) {
    fun add(kind: RunnerUiControlKind, pageId: String = pages.first().id): RunnerUiDesignerDraft {
        require(fields.size < 128) { "控件不能超过128个" }
        var n = fields.size + 1; while (fields.any { it.id == "control$n" }) n++
        val value = when (kind.wireName) { "integer" -> "0"; "boolean" -> "false"; "choice" -> "选项1"; else -> if (kind == RunnerUiControlKind.LABEL) "标签" else "" }
        val currentPage = pages.first { it.id == pageId }
        val width = ((currentPage.width - 32) / 3).coerceIn(20, 208)
        val height = 40
        val occupied = fields.filter { it.ui.pageId == pageId && it.ui.parentId == null }.map { it.ui }
        val position = (8..(currentPage.height - height - 8)).step(4).firstNotNullOfOrNull { y ->
            (0..2).map { 8 + it * (width + 8) }.firstOrNull { x -> x + width <= currentPage.width - 8 && occupied.none { p ->
                x < p.x + p.width + 4 && x + width + 4 > p.x && y < p.y + p.height + 4 && y + height + 4 > p.y
            } }?.let { it to y }
        } ?: error("当前页面没有空位，请整理布局或添加页面")
        return copy(fields = fields + RunnerUiFieldDraft("control$n", kind.label, kind, false, value,
            minimum = if (kind in setOf(RunnerUiControlKind.SLIDER, RunnerUiControlKind.PROGRESS)) "0" else "",
            maximum = if (kind in setOf(RunnerUiControlKind.SLIDER, RunnerUiControlKind.PROGRESS)) "100" else "",
            optionsText = if (kind.wireName == "choice") "选项1,选项2" else "",
            ui = UiPresentation(control = kind.control.name, pageId = pageId, x = position.first, y = position.second,
                width = width, height = height, fontPx = 20,
                events = if (kind == RunnerUiControlKind.BUTTON) mapOf("click" to "run") else emptyMap())))
    }
    fun update(index: Int, field: RunnerUiFieldDraft) = copy(fields = fields.toMutableList().also { it[index] = field })
    fun remove(index: Int): RunnerUiDesignerDraft {
        val ids = mutableSetOf(fields[index].id)
        repeat(fields.size) { fields.filter { it.ui.parentId in ids }.forEach { ids.add(it.id) } }
        return copy(fields = fields.filterNot { it.id in ids })
    }
    fun move(index: Int, offset: Int): RunnerUiDesignerDraft {
        val target = index + offset; if (index !in fields.indices || target !in fields.indices) return this
        return copy(fields = fields.toMutableList().also { it.add(target, it.removeAt(index)) })
    }

    /** Translate selected roots once: children of selected containers inherit the movement. */
    fun translate(ids: Set<String>, dx: Int, dy: Int, snap: Boolean = false): RunnerUiDesignerDraft {
        val roots = selectionRoots(ids)
        if (roots.isEmpty()) return this
        val anchor = roots.first().ui
        val requestedX = if (snap) ((anchor.x + dx) / 8f).roundToInt() * 8 - anchor.x else dx
        val requestedY = if (snap) ((anchor.y + dy) / 8f).roundToInt() * 8 - anchor.y else dy
        val moveX = requestedX.coerceIn(-roots.minOf { it.ui.x }, 4096 - roots.maxOf { it.ui.x })
        val moveY = requestedY.coerceIn(-roots.minOf { it.ui.y }, 8192 - roots.maxOf { it.ui.y })
        val rootIds = roots.map { it.id }.toSet()
        return copy(fields = fields.map { if (it.id in rootIds) it.copy(ui = it.ui.copy(x = it.ui.x + moveX, y = it.ui.y + moveY)) else it })
    }

    fun selectionRoots(ids: Set<String>) = fields.filter { it.id in ids && !hasSelectedAncestor(it, ids) }

    /** Place the selection as one group in its page/container; do not move descendants twice. */
    fun position(ids: Set<String>, operation: String): RunnerUiDesignerDraft {
        require(operation in setOf("left", "centerX", "right", "top", "centerY", "bottom", "center"))
        val roots = selectionRoots(ids)
        if (roots.isEmpty()) return this
        require(roots.map { it.ui.pageId to it.ui.parentId }.distinct().size == 1) { "页面定位请选同一页面、同一容器的控件" }
        val first = roots.first().ui
        val page = pages.single { it.id == first.pageId }
        val owner = first.parentId?.let { id -> fields.single { it.id == id }.ui }
        val width = owner?.width ?: page.width; val height = owner?.height ?: page.height
        val left = roots.minOf { it.ui.x }; val top = roots.minOf { it.ui.y }
        val groupWidth = roots.maxOf { it.ui.x + it.ui.width } - left
        val groupHeight = roots.maxOf { it.ui.y + it.ui.height } - top
        val horizontal = operation in setOf("left", "centerX", "right", "center")
        val vertical = operation in setOf("top", "centerY", "bottom", "center")
        require(!horizontal || groupWidth <= width) { "所选控件整体宽度超过页面或容器，请先缩小控件" }
        require(!vertical || groupHeight <= height) { "所选控件整体高度超过页面或容器，请先缩小控件" }
        val x = when (operation) { "left" -> 0; "right" -> width - groupWidth; "centerX", "center" -> (width - groupWidth) / 2; else -> left }
        val y = when (operation) { "top" -> 0; "bottom" -> height - groupHeight; "centerY", "center" -> (height - groupHeight) / 2; else -> top }
        val rootIds = roots.map { it.id }.toSet()
        return copy(fields = fields.map { f -> if (f.id in rootIds) f.copy(ui = f.ui.copy(x = f.ui.x + x - left, y = f.ui.y + y - top)) else f })
    }

    fun align(ids: Set<String>, operation: String): RunnerUiDesignerDraft {
        val selected = fields.filter { it.id in ids }
        if (selected.size < 2 || selected.map { it.ui.pageId to it.ui.parentId }.distinct().size != 1) return this
        val left = selected.minOf { it.ui.x }; val right = selected.maxOf { it.ui.x + it.ui.width }
        val top = selected.minOf { it.ui.y }; val bottom = selected.maxOf { it.ui.y + it.ui.height }
        return copy(fields = fields.map { f -> if (f.id !in ids) f else f.copy(ui = when (operation) {
            "left" -> f.ui.copy(x = left); "right" -> f.ui.copy(x = right - f.ui.width)
            "top" -> f.ui.copy(y = top); "bottom" -> f.ui.copy(y = bottom - f.ui.height)
            "centerX" -> f.ui.copy(x = (left + right - f.ui.width) / 2)
            "centerY" -> f.ui.copy(y = (top + bottom - f.ui.height) / 2)
            else -> f.ui
        }) })
    }

    fun distribute(ids: Set<String>, horizontal: Boolean): RunnerUiDesignerDraft {
        val selected = fields.filter { it.id in ids }.sortedBy { if (horizontal) it.ui.x else it.ui.y }
        if (selected.size < 3 || selected.map { it.ui.pageId to it.ui.parentId }.distinct().size != 1) return this
        val start = if (horizontal) selected.first().ui.x else selected.first().ui.y
        val end = selected.maxOf { if (horizontal) it.ui.x + it.ui.width else it.ui.y + it.ui.height }
        val total = selected.sumOf { if (horizontal) it.ui.width else it.ui.height }
        val gap = (end - start - total).toFloat() / (selected.size - 1)
        if (gap < 0) return this
        var cursor = start.toFloat()
        val positions = selected.associate { f -> val value = cursor.roundToInt(); cursor += (if (horizontal) f.ui.width else f.ui.height) + gap; f.id to value }
        return copy(fields = fields.map { f -> positions[f.id]?.let { if (horizontal) f.copy(ui = f.ui.copy(x = it)) else f.copy(ui = f.ui.copy(y = it)) } ?: f })
    }

    fun compactPage(pageId: String): RunnerUiDesignerDraft {
        val page = pages.single { it.id == pageId }
        val roots = fields.filter { it.ui.pageId == pageId && it.ui.parentId == null }
        if (roots.isEmpty()) return this
        val width = ((page.width - 32) / 3).coerceAtLeast(20)
        var y = 8
        val changes = mutableMapOf<String, UiPresentation>()
        roots.chunked(3).forEach { row ->
            val height = row.maxOf { it.ui.height }
            row.forEachIndexed { i, f ->
                require(f.kind != RunnerUiControlKind.CONTAINER || f.ui.width <= width) { "容器过宽，请先调整容器尺寸，避免裁剪内部控件" }
                changes[f.id] = f.ui.copy(x = 8 + i * (width + 8), y = y, width = width)
            }
            y += height + 8
        }
        require(y <= page.height) { "内容超出页面高度，请先缩小控件或增大页面；布局未改变" }
        return copy(fields = fields.map { it.copy(ui = changes[it.id] ?: it.ui) })
    }

    /** Explicit undoable operation; never shrink persisted user layouts on load. */
    fun shrinkAll(): RunnerUiDesignerDraft = copy(fields = fields.map { f ->
        fun reduced(value: Int, minimum: Int) = (value * .75f).roundToInt().coerceAtLeast(minimum)
        f.copy(ui = f.ui.copy(x = reduced(f.ui.x, 0), y = reduced(f.ui.y, 0),
            width = reduced(f.ui.width, 20), height = reduced(f.ui.height, 20), fontPx = reduced(f.ui.fontPx, 8)))
    })

    fun layer(ids: Set<String>, front: Boolean): RunnerUiDesignerDraft {
        val selected = fields.filter { it.id in ids }; val others = fields.filterNot { it.id in ids }
        return copy(fields = if (front) others + selected else selected + others)
    }

    fun absolutePosition(field: RunnerUiFieldDraft): Pair<Int, Int> {
        var x = field.ui.x; var y = field.ui.y; var parent = field.ui.parentId; val seen = mutableSetOf(field.id)
        while (parent != null && seen.add(parent)) {
            val owner = fields.firstOrNull { it.id == parent } ?: break
            x += owner.ui.x; y += owner.ui.y; parent = owner.ui.parentId
        }
        return x to y
    }

    /** Containers paint before descendants; siblings retain their runtime stacking order. */
    fun paintOrder(pageId: String): List<RunnerUiFieldDraft> {
        val pageFields = fields.filter { it.ui.pageId == pageId }
        val seen = mutableSetOf<String>(); val result = mutableListOf<RunnerUiFieldDraft>()
        fun visit(field: RunnerUiFieldDraft) {
            if (!seen.add(field.id)) return
            result.add(field)
            pageFields.filter { it.ui.parentId == field.id }.forEach(::visit)
        }
        pageFields.filter { it.ui.parentId == null }.forEach(::visit)
        pageFields.filterNot { it.id in seen }.forEach(::visit)
        return result
    }

    fun reparent(id: String, parentId: String?, pageId: String): RunnerUiDesignerDraft {
        val field = fields.single { it.id == id }
        val descendants = mutableSetOf(id)
        repeat(fields.size) { fields.filter { it.ui.parentId in descendants }.forEach { descendants.add(it.id) } }
        require(parentId !in descendants) { "不能把控件放入自己或子容器" }
        val owner = parentId?.let { parent -> fields.single { it.id == parent }.also {
            require(it.kind == RunnerUiControlKind.CONTAINER && it.ui.pageId == pageId) { "请选择同页面容器" }
        } }
        val (x, y) = absolutePosition(field)
        val origin = owner?.let(::absolutePosition) ?: (0 to 0)
        return copy(fields = fields.map { f -> when {
            f.id == id -> f.copy(ui = f.ui.copy(parentId = parentId, pageId = pageId, x = (x - origin.first).coerceIn(0,4096), y = (y - origin.second).coerceIn(0,8192)))
            f.id in descendants -> f.copy(ui = f.ui.copy(pageId = pageId))
            else -> f
        } })
    }

    private fun hasSelectedAncestor(field: RunnerUiFieldDraft, ids: Set<String>): Boolean {
        var parent = field.ui.parentId; val seen = mutableSetOf<String>()
        while (parent != null && seen.add(parent)) {
            if (parent in ids) return true
            parent = fields.firstOrNull { it.id == parent }?.ui?.parentId
        }
        return false
    }
}
internal fun runnerUiDesignerDraft(value: JsonObject?): RunnerUiDesignerDraft {
    if (value == null) return RunnerUiDesignerDraft()
    val definition = ScriptUiDefinition.parse(value.toString())
    return RunnerUiDesignerDraft(definition.description.orEmpty(), definition.fields.mapIndexed { i, f ->
        val kind = RunnerUiControlKind.entries.single { it.control == f.control }
        RunnerUiFieldDraft(f.id, f.label, kind, f.required, f.initialValue, f.minimum?.toString().orEmpty(), f.maximum?.toString().orEmpty(), f.options.joinToString(","),
            f.ui ?: UiPresentation(control = f.control.name, y = 20 + i * 84))
    }, definition.pages)
}
internal fun RunnerUiDesignerDraft.toRunnerUiJson(): JsonObject? {
    if (fields.isEmpty()) return null
    val definition = ScriptUiDefinition(description.takeIf(String::isNotBlank), fields.map { f ->
        fun bound(value: String): Long? = if (value.isBlank()) null else value.toLongOrNull() ?: error("${f.label} 范围必须是整数")
        UiField(f.id, f.label, f.kind.wireName, f.required, f.initialText, bound(f.minimum), bound(f.maximum),
            if (f.kind.wireName == "choice") f.optionsText.split(',').map(String::trim).filter(String::isNotBlank) else emptyList(), f.ui.copy(control = f.kind.control.name))
    }, 2, pages)
    definition.validate(); return definition.toJson()
}
