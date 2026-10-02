package com.autoscript.script.ui

import android.content.Context
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File
import kotlin.math.roundToInt

/** No engine, project-store or Compose dependencies. A host owns lifecycle and event authorization. */
class ScriptUiView(
    context: Context, val definition: ScriptUiDefinition,
    initial: Map<String, String> = definition.initialValues(),
    private val image: (String) -> File? = { null },
    private val onEvent: (id: String, event: String, value: String, action: String?) -> Unit,
) : LinearLayout(context) {
    private val values = initial.toMutableMap()
    private val widgets = mutableMapOf<String, View>()
    private val visibilityOverrides = mutableMapOf<String, Boolean>()
    private val enabledOverrides = mutableMapOf<String, Boolean>()
    private val optionOverrides = mutableMapOf<String, List<String>>()
    private var pageId = definition.pages.first().id
    private var updating = false
    private var runtime = false
    var displayMode: UiDisplayMode = UiDisplayMode.FIT_PAGE
        private set
    var onPageChanged: (() -> Unit)? = null
    private var renderScale = 1f
    private var renderPending = false
    private var restorePending = false
    private var renderRevision = 0L
    private var interactionEnabled = true
    private val scrollPositions = mutableMapOf<String, Int>()
    private var remainingImagePixels = 8 * 1024 * 1024
    private val pageBody = FrameLayout(context)
    private val pageViewport = FrameLayout(context)
    private val pageScroll = ScrollView(context)
    private val actions = LinearLayout(context)
    init {
        orientation = VERTICAL
        setBackgroundColor(Color.WHITE)
        definition.description?.takeIf { it.isNotBlank() }?.let { addView(TextView(context).apply { text = it; textSize = 11f; maxLines = 2; gravity = Gravity.CENTER_VERTICAL; setPadding(8.dp(), 0, 8.dp(), 0) }, LayoutParams(-1, 28.dp())) }
        if (definition.pages.size > 1) {
            val tabs = LinearLayout(context)
            definition.pages.forEach { page -> tabs.addView(windowButton(page.title).apply { setOnClickListener { switchPage(page.id) } }, LayoutParams(96.dp(), 32.dp())) }
            addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(tabs) }, LayoutParams(-1, 32.dp()))
        }
        pageViewport.addView(pageBody)
        pageScroll.addView(pageViewport)
        addView(pageScroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        actions.orientation = HORIZONTAL; addView(actions)
        renderActions()
        scheduleRender()
        pageScroll.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob -> if (r - l != or - ol || b - t != ob - ot) scheduleRender() }
        pageScroll.setOnScrollChangeListener { _: View, _: Int, y: Int, _: Int, _: Int ->
            if (!updating && !restorePending && (definition.version == 1 || displayMode == UiDisplayMode.WIDTH_SCROLL)) scrollPositions[pageId] = y
        }
    }
    fun values(): Map<String, String> = values.toMap()
    fun setRuntime(running: Boolean) { runtime = running; renderActions() }
    fun currentPage() = definition.pages.single { it.id == pageId }
    fun switchPage(id: String) {
        require(definition.pages.any { it.id == id })
        if (id == pageId) return
        if (!restorePending && (definition.version == 1 || displayMode == UiDisplayMode.WIDTH_SCROLL)) scrollPositions[pageId] = pageScroll.scrollY
        scrollPositions.putIfAbsent(id, 0)
        pageId = id; renderPage(); onPageChanged?.invoke()
    }
    fun setDisplayMode(mode: UiDisplayMode) {
        if (displayMode == mode) return
        if (!restorePending && displayMode == UiDisplayMode.WIDTH_SCROLL) scrollPositions[pageId] = pageScroll.scrollY
        displayMode = mode; renderPage()
    }
    fun resetDefaults() {
        values.clear(); values.putAll(definition.initialValues())
        visibilityOverrides.clear(); enabledOverrides.clear(); optionOverrides.clear(); scrollPositions.clear()
        scrollPositions[pageId] = 0
        renderPage()
    }
    fun setInteractionEnabled(enabled: Boolean) { interactionEnabled = enabled; renderActions(); renderPage() }
    private fun scheduleRender() {
        if (renderPending) return
        renderPending = true
        pageScroll.post { renderPending = false; renderPage() }
    }
    fun applyCommand(id: String, operation: String, value: String) {
        if (operation == "page") { switchPage(value); return }
        val field = definition.fields.singleOrNull { it.id == id } ?: return
        val widget = widgets[id]
        when (operation) {
            "value", "text" -> {
                when (field.kind) {
                    "integer" -> require(value.toLongOrNull()?.let { it in (field.minimum ?: -1_000_000_000)..(field.maximum ?: 1_000_000_000) } == true)
                    "boolean" -> require(value in setOf("true", "false"))
                    "choice" -> require(value in options(field))
                    else -> require(value.length <= 2048 && '\u0000' !in value)
                }
                values[id] = value; updating = true
                when (widget) {
                    is EditText -> widget.setText(value)
                    is CompoundButton -> widget.isChecked = value == "true"
                    is Spinner -> widget.setSelection(options(field).indexOf(value).coerceAtLeast(0))
                    is SeekBar -> widget.progress = ((value.toLongOrNull() ?: 0) - (field.minimum ?: 0)).toInt()
                    is ProgressBar -> widget.progress = value.toIntOrNull() ?: 0
                    is TextView -> widget.text = value.ifEmpty { field.label }
                    else -> renderPage()
                }; updating = false }
            "visible" -> { require(value in setOf("true", "false")); visibilityOverrides[id] = value == "true"; widget?.visibility = if (value == "true") View.VISIBLE else View.INVISIBLE }
            "enabled" -> { require(value in setOf("true", "false")); enabledOverrides[id] = value == "true"; renderPage() }
            "items" -> {
                require(field.kind == "choice")
                val updated = value.split('\n'); require(updated.size in 1..64 && updated.distinct().size == updated.size && updated.all { it.length in 1..128 })
                optionOverrides[id] = updated
                if (values[id] !in updated) values[id] = updated.first()
                renderPage()
            }
            "progress" -> { require(field.control == UiControl.PROGRESS); val progress = value.toIntOrNull() ?: error("进度需要整数"); require(progress in 0..(field.maximum ?: 100).toInt()); values[id] = value; if (widget is ProgressBar) widget.progress = progress }
        }
    }
    private fun renderActions() {
        actions.removeAllViews()
        fun action(text: String, action: String) { actions.addView(windowButton(text).apply { isEnabled = interactionEnabled; setOnClickListener { onEvent("window", "click", "", action) } }, LayoutParams(0, 32.dp(), 1f)) }
        action(if (runtime) "停止" else "取消", "close")
        if (runtime) action("最小化", "minimize") else action("运行", "run")
    }
    private fun emit(f: UiField, event: String, value: String = values[f.id].orEmpty()) {
        if (updating || !interactionEnabled) return
        var current: UiField? = f
        while (current != null) {
            val item = current
            if (!(enabledOverrides[item.id] ?: item.ui?.enabled ?: true) || !(visibilityOverrides[item.id] ?: item.ui?.visible ?: true)) return
            current = item.ui?.parentId?.let { id -> definition.fields.find { it.id == id } }
        }
        values[f.id] = value
        val action = f.ui?.events?.get(event)
        if (action?.startsWith("page:") == true) { switchPage(action.removePrefix("page:")); return }
        onEvent(f.id, event, value, action)
    }
    private fun renderPage() {
        if (pageScroll.width <= 0 || pageScroll.height <= 0) return
        val focusEntry = widgets.entries.firstOrNull { it.value.hasFocus() && it.value is EditText }
        val focused = focusEntry?.key
        val selection = (focusEntry?.value as? EditText)?.let { it.selectionStart to it.selectionEnd }
        val scroll = if (displayMode == UiDisplayMode.FIT_PAGE && definition.version == 2) 0 else scrollPositions[pageId] ?: pageScroll.scrollY
        restorePending = true; renderRevision++
        updating = true
        remainingImagePixels = 8 * 1024 * 1024
        pageBody.removeAllViews(); widgets.clear()
        pageViewport.minimumHeight = pageScroll.height
        if (definition.version == 1) {
            val column = LinearLayout(context).apply { orientation = VERTICAL; setPadding(12.dp(), 4.dp(), 12.dp(), 8.dp()) }
            definition.fields.forEach { field ->
                column.addView(TextView(context).apply { text = field.label; setPadding(0, 8.dp(), 0, 2.dp()) })
                val widget = widget(field); widget.isEnabled = enabled(field); widgets[field.id] = widget; column.addView(widget, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
            pageBody.layoutParams = FrameLayout.LayoutParams(-1, -2)
            pageViewport.layoutParams = FrameLayout.LayoutParams(-1, -2)
            pageBody.addView(column); updating = false; restoreFocus(focused, selection, scroll); return
        }
        val page = currentPage()
        val viewport = ScriptUiGeometry.viewport(page.width, page.height, pageScroll.width.toFloat(), pageScroll.height.toFloat(), displayMode)
        val scale = viewport.scale; renderScale = scale
        pageViewport.layoutParams = FrameLayout.LayoutParams(-1, maxOf(pageScroll.height, (viewport.top + viewport.height).roundToInt()))
        pageBody.layoutParams = FrameLayout.LayoutParams(viewport.width.roundToInt().coerceAtLeast(1), viewport.height.roundToInt().coerceAtLeast(1)).apply {
            leftMargin = viewport.left.roundToInt(); topMargin = viewport.top.roundToInt()
        }
        pageBody.setBackgroundColor(Color.parseColor(page.background))
        val remaining = definition.fields.filter { it.ui?.pageId == pageId }.toMutableList()
        while (remaining.isNotEmpty()) {
            val f = remaining.firstOrNull { it.ui?.parentId == null || widgets[it.ui?.parentId] is FrameLayout } ?: break
            remaining.remove(f)
            val p = requireNotNull(f.ui); val widget = widget(f); widgets[f.id] = widget
            widget.visibility = if (visibilityOverrides[f.id] ?: p.visible) View.VISIBLE else View.INVISIBLE
            widget.isEnabled = enabled(f)
            widget.background = GradientDrawable().apply { setColor(Color.parseColor(p.background)); cornerRadius = 6 * scale }
            if (widget is TextView) {
                styleText(widget, f)
            }
            if (widget is RadioGroup) for (i in 0 until widget.childCount) {
                (widget.getChildAt(i) as? RadioButton)?.apply {
                    styleText(this, f); buttonDrawable = compactIndicator(scale, circle = true)
                    layoutParams = if (widget.orientation == HORIZONTAL) RadioGroup.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                        else RadioGroup.LayoutParams(LayoutParams.MATCH_PARENT, ((p.fontPx * 1.8f).coerceAtLeast(20f) * scale).roundToInt().coerceAtLeast(1))
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    isEnabled = widget.isEnabled
                }
            }
            val params = FrameLayout.LayoutParams((p.width * scale).roundToInt().coerceAtLeast(1), (p.height * scale).roundToInt().coerceAtLeast(1)).apply {
                leftMargin = (p.x * scale).roundToInt(); topMargin = (p.y * scale).roundToInt()
            }
            val parent = p.parentId?.let { widgets[it] as? FrameLayout } ?: pageBody
            parent.addView(widget, params)
        }
        updating = false
        restoreFocus(focused, selection, scroll)
    }
    private fun restoreFocus(id: String?, selection: Pair<Int, Int>?, scroll: Int) {
        (id?.let { widgets[it] } as? EditText)?.let { edit ->
            edit.requestFocus()
            selection?.let { edit.setSelection(it.first.coerceIn(0, edit.length()), it.second.coerceIn(0, edit.length())) }
        }
        val page = pageId
        val mode = displayMode
        val revision = renderRevision
        pageScroll.post {
            if (pageId == page && displayMode == mode && renderRevision == revision) {
                pageScroll.scrollTo(0, scroll)
                restorePending = false
                if (definition.version == 1 || mode == UiDisplayMode.WIDTH_SCROLL) scrollPositions[page] = pageScroll.scrollY
            }
        }
    }
    private fun widget(f: UiField): View {
        val value = values[f.id] ?: f.initialValue
        val choices = options(f)
        val view: View = when (f.control) {
            UiControl.INPUT, UiControl.TEXTAREA, UiControl.NUMBER -> EditText(context).apply {
                hint = f.label; setText(value); setSingleLine(f.control != UiControl.TEXTAREA)
                inputType = if (f.kind == "integer") android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                    else android.text.InputType.TYPE_CLASS_TEXT or if (f.control == UiControl.TEXTAREA) android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
                addTextChangedListener(object : TextWatcher { override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) { emit(f, "change", s?.toString().orEmpty().take(2048)) } })
            }
            UiControl.LABEL -> TextView(context).apply { text = value.ifEmpty { f.label } }
            UiControl.BUTTON, UiControl.NAVIGATION -> Button(context).apply { text = value.ifEmpty { f.label }; setOnClickListener { emit(f, "click") } }
            UiControl.CONTAINER -> FrameLayout(context)
            UiControl.CHECKBOX -> CheckBox(context).apply { text = f.label; isChecked = value == "true"; setOnCheckedChangeListener { _, checked -> emit(f, "change", checked.toString()) } }
            UiControl.RADIO -> RadioGroup(context).apply {
                orientation = if (f.ui?.let { ScriptUiGeometry.radioHorizontal(it.height, it.fontPx, choices.size) } == true) HORIZONTAL else VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                choices.forEachIndexed { index, option -> addView(RadioButton(context).apply { id = index + 1; text = option; isChecked = option == value }) }
                setOnCheckedChangeListener { _, id -> choices.getOrNull(id - 1)?.let { emit(f, "selection", it) } }
            }
            UiControl.SELECT -> Spinner(context).apply {
                adapter = choicesAdapter(f, android.R.layout.simple_spinner_dropdown_item, choices); setSelection(choices.indexOf(value).coerceAtLeast(0), false)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { choices.getOrNull(position)?.let { if (values[f.id] != it) emit(f, "selection", it) } }
                }
            }
            UiControl.LIST -> ListView(context).apply {
                choiceMode = ListView.CHOICE_MODE_SINGLE; adapter = choicesAdapter(f, android.R.layout.simple_list_item_single_choice, choices)
                setItemChecked(choices.indexOf(value).coerceAtLeast(0), true); setOnItemClickListener { _, _, i, _ -> choices.getOrNull(i)?.let { emit(f, "selection", it) } }
            }
            UiControl.SLIDER -> SeekBar(context).apply {
                val min = f.minimum ?: 0; max = ((f.maximum ?: 100) - min).coerceIn(1, 1_000_000).toInt(); progress = ((value.toLongOrNull() ?: min) - min).toInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onStartTrackingTouch(bar: SeekBar?) {}; override fun onStopTrackingTouch(bar: SeekBar?) {}
                    override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) emit(f, "change", (progress + min).toString()) }
                })
            }
            UiControl.PROGRESS -> ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { max = (f.maximum ?: 100).coerceIn(1, 1_000_000).toInt(); progress = value.toIntOrNull() ?: 0 }
            UiControl.IMAGE -> ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                image(value)?.takeIf { it.isFile && it.length() <= 16 * 1024 * 1024 }?.let { file ->
                    val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(file.path, info)
                    if (info.outWidth in 1..4096 && info.outHeight in 1..4096) {
                        var sample = 1
                        while (maxOf(info.outWidth, info.outHeight) / sample > 512) sample *= 2
                        val pixels = ((info.outWidth + sample - 1) / sample) * ((info.outHeight + sample - 1) / sample)
                        if (pixels <= remainingImagePixels) { remainingImagePixels -= pixels; setImageBitmap(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })) }
                    }
                }
                contentDescription = f.label
            }
        }
        if (f.control !in setOf(UiControl.BUTTON, UiControl.NAVIGATION) && f.ui?.events?.containsKey("click") == true) view.setOnClickListener { emit(f, "click") }
        if (f.ui?.events?.containsKey("longClick") == true) view.setOnLongClickListener { emit(f, "longClick"); true }
        return view
    }
    private fun Int.dp() = (this * resources.displayMetrics.density).roundToInt()
    private fun options(field: UiField) = optionOverrides[field.id] ?: field.options
    private fun enabled(field: UiField): Boolean {
        if (!interactionEnabled) return false
        var current: UiField? = field
        while (current != null) {
            val item = current
            if (!(enabledOverrides[item.id] ?: item.ui?.enabled ?: true)) return false
            current = item.ui?.parentId?.let { id -> definition.fields.find { it.id == id } }
        }
        return true
    }
    private fun styleText(view: TextView, field: UiField) {
        val style = field.ui ?: return
        val scale = renderScale
        view.setTextColor(Color.parseColor(style.textColor))
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, style.fontPx * scale)
        view.gravity = Gravity.CENTER_VERTICAL or when (style.alignment) { "left" -> Gravity.START; "right" -> Gravity.END; else -> Gravity.CENTER_HORIZONTAL }
        view.minWidth = 0; view.minHeight = 0; view.minimumWidth = 0; view.minimumHeight = 0
        view.setPadding((4 * scale).toInt(), 0, (4 * scale).toInt(), 0)
        view.includeFontPadding = false
        if (view is CheckBox) view.buttonDrawable = compactIndicator(scale, circle = false)
        if (view is CheckedTextView) view.checkMarkDrawable = compactIndicator(scale, circle = true)
        view.isEnabled = enabled(field)
    }
    private fun windowButton(label: String) = Button(context).apply {
        text = label; textSize = 12f; minWidth = 0; minHeight = 0; minimumWidth = 0; minimumHeight = 0
        includeFontPadding = false; gravity = Gravity.CENTER; setPadding(4.dp(), 0, 4.dp(), 0)
    }
    private fun compactIndicator(scale: Float, circle: Boolean): StateListDrawable {
        val size = (18 * scale).roundToInt().coerceAtLeast(1)
        fun shape(checked: Boolean) = GradientDrawable().apply {
            setSize(size, size)
            shape = if (circle) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
            cornerRadius = 3 * scale
            setColor(if (checked) Color.rgb(56, 106, 255) else Color.TRANSPARENT)
            setStroke(scale.roundToInt().coerceAtLeast(1), Color.rgb(56, 106, 255))
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), shape(true)); addState(intArrayOf(), shape(false))
        }
    }
    private fun choicesAdapter(field: UiField, layout: Int, choices: List<String>) = object : ArrayAdapter<String>(context, layout, choices) {
        private fun compact(view: View, popup: Boolean): View = view.also {
            (it as? TextView)?.let { text ->
                styleText(text, field)
                field.ui?.let { style ->
                    val scale = renderScale
                    val height = ((style.fontPx * 1.8f).coerceAtLeast(20f) * scale).roundToInt().coerceAtLeast(1)
                    text.layoutParams?.height = if (popup) height.coerceAtLeast(24.dp()) else height
                }
            }
        }
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = compact(super.getView(position, convertView, parent), false)
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = compact(super.getDropDownView(position, convertView, parent), true)
    }
}
