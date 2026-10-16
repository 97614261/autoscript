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
    private val commandState = ScriptUiCommandState(definition, initial)
    private val backgrounds=ScriptUiBackgrounds(image)
    private val values = commandState.values
    private val widgets = mutableMapOf<String, View>()
    private val visibilityOverrides = commandState.visibility
    private val enabledOverrides = commandState.enabled
    private val optionOverrides = commandState.items
    private var pageId = definition.pages.first().id
    private var updating = false
    private var runtime = false
    private var dialogMode = false
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
    fun readProperty(id: String, property: ScriptUiRuntimeProperty): String = commandState.read(id, property)
    fun readExtension(id: String, operation: String): String = when(operation) {
        "get:groupCount" -> commandState.group(id).size.toString()
        "get:backgroundImage" -> {require(definition.fields.any {it.id==id && it.ui!=null}); backgrounds.read(id)}
        else -> error("无效的界面读取")
    }
    fun exportConfig(): String=commandState.exportConfig()
    fun restoreConfig(text: String) {commandState.restoreConfig(text); renderPage()}
    fun setRuntime(running: Boolean) { runtime = running; renderActions() }
    fun setDialogMode(value: Boolean) { dialogMode = value; renderActions() }
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
        commandState.reset(); backgrounds.reset(); scrollPositions.clear()
        scrollPositions[pageId] = 0
        renderPage()
    }
    fun setInteractionEnabled(enabled: Boolean) { interactionEnabled = enabled; renderActions(); renderPage() }
    private fun scheduleRender() {
        if (renderPending) return
        renderPending = true
        pageScroll.post { renderPending = false; renderPage(reuseWidgets = true) }
    }
    fun applyCommand(id: String, operation: String, value: String) {
        if (operation == "page") { switchPage(value); return }
        if(operation=="backgroundImage") {
            require(definition.fields.any {it.id==id && it.ui!=null}) {"背景图请选择设计控件"}
            backgrounds.apply(id,value); renderPage(); return
        }
        if(operation in setOf("groupVisible","groupEnabled","groupReset")) {
            commandState.applyGroup(id,operation,value); renderPage(); return
        }
        val field = commandState.apply(id, operation, value)
        val widget = widgets[id]
        when (operation) {
            "value", "text" -> {
                updating = true
                try { when (widget) {
                    is EditText -> widget.setText(value)
                    is CompoundButton -> widget.isChecked = value == "true"
                    is Spinner -> widget.setSelection(commandState.spinnerPosition(field), false)
                    is SeekBar -> widget.progress = (((value.toLongOrNull() ?: 0) - (field.minimum ?: 0))/commandState.step(field)).toInt()
                    is ProgressBar -> widget.progress = value.toIntOrNull() ?: 0
                    is TextView -> widget.text = value.ifEmpty { field.label }
                    else -> renderPage()
                } } finally { updating = false }
            }
            "visible" -> { widget?.visibility = if (value == "true") View.VISIBLE else View.INVISIBLE }
            "enabled", "items" -> renderPage()
            "progress" -> if (widget is ProgressBar) widget.progress = value.toInt()
            else -> renderPage()
        }
    }
    private fun renderActions() {
        actions.removeAllViews()
        fun action(text: String, action: String) { actions.addView(windowButton(text).apply { isEnabled = interactionEnabled; setOnClickListener { onEvent("window", "click", "", action) } }, LayoutParams(0, 32.dp(), 1f)) }
        if (dialogMode) { action("关闭", "close"); return }
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
        if (action?.startsWith("page:") == true) {
            // Page navigation must still synchronize its control value/binding with the host.
            onEvent(f.id, event, value, action)
            switchPage(action.removePrefix("page:")); return
        }
        onEvent(f.id, event, value, action)
    }
    private fun renderPage(reuseWidgets: Boolean = false) {
        if (pageScroll.width <= 0 || pageScroll.height <= 0) return
        val focusEntry = widgets.entries.firstOrNull { it.value.hasFocus() && it.value is EditText }
        val focused = focusEntry?.key
        val selection = (focusEntry?.value as? EditText)?.let { it.selectionStart to it.selectionEnd }
        val scroll = if (displayMode == UiDisplayMode.FIT_PAGE && definition.version == 2) 0 else scrollPositions[pageId] ?: pageScroll.scrollY
        restorePending = true; renderRevision++
        updating = true
        remainingImagePixels = 8 * 1024 * 1024
        val fields = definition.fields.map(commandState::effective).filter { definition.version == 1 || it.ui?.pageId == pageId }
        val reuse = reuseWidgets && pageBody.childCount > 0 && widgets.keys == fields.map { it.id }.toSet()
        if (!reuse) { pageBody.removeAllViews(); widgets.clear() }
        pageViewport.minimumHeight = pageScroll.height
        if (definition.version == 1) {
            if (reuse) { updating = false; restoreFocus(focused, selection, scroll); return }
            val column = LinearLayout(context).apply { orientation = VERTICAL; setPadding(12.dp(), 4.dp(), 12.dp(), 8.dp()) }
            definition.fields.map(commandState::effective).forEach { field ->
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
        val remaining = fields.toMutableList()
        while (remaining.isNotEmpty()) {
            val f = remaining.firstOrNull { it.ui?.parentId == null || widgets[it.ui?.parentId] is FrameLayout } ?: break
            remaining.remove(f)
            val p = requireNotNull(commandState.presentation(f))
            val widget = if (reuse) widgets.getValue(f.id) else widget(f).also { widgets[f.id] = it }
            widget.visibility = if (visibilityOverrides[f.id] ?: p.visible) View.VISIBLE else View.INVISIBLE
            widget.isEnabled = enabled(f)
            widget.background = backgrounds.drawable(f.id,resources,GradientDrawable().apply {
                setColor(Color.parseColor(p.background)); cornerRadius = p.cornerPx * scale
                if (p.borderWidthPx > 0) setStroke((p.borderWidthPx * scale).roundToInt().coerceAtLeast(1), Color.parseColor(p.borderColor))
            })
            if (widget is TextView) {
                styleText(widget, f)
            }
            if (widget is ProgressBar) widget.useCompactTrack(scale)
            val params = FrameLayout.LayoutParams((p.width * scale).roundToInt().coerceAtLeast(1), (p.height * scale).roundToInt().coerceAtLeast(1)).apply {
                leftMargin = (p.x * scale).roundToInt(); topMargin = (p.y * scale).roundToInt()
            }
            val parent = p.parentId?.let { widgets[it] as? FrameLayout } ?: pageBody
            if (widget.parent === parent) widget.layoutParams = params else parent.addView(widget, params)
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
            UiControl.INPUT, UiControl.PASSWORD, UiControl.TEXTAREA, UiControl.NUMBER, UiControl.DECIMAL -> EditText(context).apply {
                hint = commandState.hint(f); setText(value); setSingleLine(f.control != UiControl.TEXTAREA)
                inputType = if (f.kind == "integer") android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                    else android.text.InputType.TYPE_CLASS_TEXT or if (f.control == UiControl.TEXTAREA) android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
                if (f.kind == "number") inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                if (f.control == UiControl.PASSWORD) {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                    transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
                    if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                }
                addTextChangedListener(object : TextWatcher { override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) { emit(f, "change", s?.toString().orEmpty().take(2048)) } })
            }
            UiControl.LABEL -> TextView(context).apply { text = value.ifEmpty { f.label } }
            UiControl.BUTTON, UiControl.NAVIGATION -> Button(context).apply { text = value.ifEmpty { f.label }; setOnClickListener { emit(f, "click") } }
            UiControl.CONTAINER -> FrameLayout(context)
            UiControl.CHECKBOX -> CheckBox(context).apply { text = f.label; isChecked = value == "true"; setOnCheckedChangeListener { _, checked -> emit(f, "change", checked.toString()) } }
            UiControl.SWITCH -> Switch(context).apply { text = f.label; isChecked = value == "true"; setOnCheckedChangeListener { _, checked -> emit(f, "change", checked.toString()) } }
            UiControl.RADIO -> radioWidget(f, choices, value)
            UiControl.SELECT -> object : Spinner(context) {
                override fun performClick(): Boolean {
                    val handled = super.performClick()
                    if (handled && f.ui?.events?.containsKey("click") == true) emit(f, "click")
                    return handled
                }
            }.apply {
                adapter = choicesAdapter(f, android.R.layout.simple_spinner_dropdown_item, listOf("未选择") + choices, emptyChoice = true)
                setSelection(commandState.spinnerPosition(f), false)
                if (f.ui?.events?.containsKey("longClick") == true) setOnLongClickListener { emit(f, "longClick"); true }
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        choices.getOrNull(position - 1)?.let { if (values[f.id] != it) emit(f, "selection", it) }
                    }
                }
            }
            UiControl.LIST -> ChoiceListView(context).apply {
                if (commandState.presentation(f) != null) { divider = null; dividerHeight = 0; setPadding(0, 0, 0, 0) }
                choiceMode = ListView.CHOICE_MODE_SINGLE; adapter = choicesAdapter(f, android.R.layout.simple_list_item_single_choice, choices)
                val selected = commandState.selectedOptionIndex(f)
                if (selected >= 0) setItemChecked(selected, true) else clearChoices()
                setOnItemClickListener { _, _, i, _ -> choices.getOrNull(i)?.let {
                    emit(f, "selection", it)
                    if (f.ui?.events?.containsKey("click") == true) emit(f, "click", it)
                } }
                if (f.ui?.events?.containsKey("longClick") == true) setOnItemLongClickListener { _, _, _, _ -> emit(f, "longClick"); true }
            }
            UiControl.MULTISELECT -> ChoiceListView(context).apply {
                if (commandState.presentation(f) != null) { divider = null; dividerHeight = 0; setPadding(0, 0, 0, 0) }
                choiceMode = ListView.CHOICE_MODE_MULTIPLE
                adapter = choicesAdapter(f, android.R.layout.simple_list_item_multiple_choice, choices)
                val selected = uiSelections(value, choices)
                choices.forEachIndexed { index, item -> setItemChecked(index, item in selected) }
                setOnItemClickListener { _, _, _, _ ->
                    val next = runCatching { uiSelectionsJson(choices.filterIndexed { index, _ -> isItemChecked(index) }) }.getOrElse {
                        val prior = uiSelections(values[f.id] ?: f.initialValue, choices)
                        choices.forEachIndexed { index, item -> setItemChecked(index, item in prior) }
                        Toast.makeText(context, "多选内容超过2048字符，请减少选项", Toast.LENGTH_SHORT).show()
                        return@setOnItemClickListener
                    }
                    emit(f, "selection", next)
                    if (f.ui?.events?.containsKey("click") == true) emit(f, "click", next)
                }
                if (f.ui?.events?.containsKey("longClick") == true) setOnItemLongClickListener { _, _, _, _ -> emit(f, "longClick"); true }
            }
            UiControl.SLIDER -> SeekBar(context).apply {
                val min = f.minimum ?: 0; val step=commandState.step(f)
                max = (((f.maximum ?: 100) - min)/step).coerceIn(1, 1_000_000).toInt(); progress = (((value.toLongOrNull() ?: min) - min)/step).toInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onStartTrackingTouch(bar: SeekBar?) {}
                    override fun onStopTrackingTouch(bar: SeekBar?) { if (f.ui?.events?.containsKey("click") == true) emit(f, "click") }
                    override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) emit(f, "change", (progress.toLong()*step + min).toString()) }
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
        if (f.control !in setOf(UiControl.BUTTON, UiControl.NAVIGATION, UiControl.RADIO, UiControl.LIST, UiControl.MULTISELECT, UiControl.SELECT, UiControl.SLIDER) && f.ui?.events?.containsKey("click") == true) view.setOnClickListener { emit(f, "click") }
        if (f.control !in setOf(UiControl.RADIO, UiControl.LIST, UiControl.MULTISELECT, UiControl.SELECT) && f.ui?.events?.containsKey("longClick") == true) view.setOnLongClickListener { emit(f, "longClick"); true }
        return view
    }
    private fun Int.dp() = (this * resources.displayMetrics.density).roundToInt()
    private fun radioWidget(field: UiField, choices: List<String>, value: String): View {
        val p = commandState.presentation(field)
        val buttons = choices.mapIndexed { index, option -> RadioButton(context).apply {
            id = index + 1; text = option; isChecked = option == value; styleText(this, field)
            if (p != null) {
                buttonDrawable = compactIndicator(renderScale, true)
                setSingleLine(false); maxLines = Int.MAX_VALUE; ellipsize = null; breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
                minHeight = (maxOf(20f, p.fontPx * 1.8f) * renderScale).roundToInt().coerceAtLeast(1)
                val inset = (4 * renderScale).roundToInt(); setPadding(inset, inset, inset, inset)
            }
            isEnabled = enabled(field)
            if (field.ui?.events?.containsKey("click") == true) setOnClickListener { emit(field, "click", option) }
            if (field.ui?.events?.containsKey("longClick") == true) setOnLongClickListener { emit(field, "longClick"); true }
        } }
        val horizontal = p != null && ScriptUiGeometry.radioHorizontal(p.width, p.height, p.fontPx, buttons.map { it.paint.measureText(it.text.toString()) / renderScale })
        val group = RadioGroup(context).apply {
            orientation = if (horizontal) HORIZONTAL else VERTICAL; gravity = Gravity.CENTER_VERTICAL
            buttons.forEach { addView(it, RadioGroup.LayoutParams(if (horizontal) LayoutParams.WRAP_CONTENT else LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)) }
            setOnCheckedChangeListener { _, id -> choices.getOrNull(id - 1)?.let { emit(field, "selection", it) } }
        }
        if (p == null || horizontal) return group
        return ChoiceScrollView(context).apply { isFillViewport = true; addView(group, FrameLayout.LayoutParams(-1, -2)) }
    }
    // Child controls receive DOWN before the enclosing page can intercept their scrolling gesture.
    private class ChoiceScrollView(context: Context) : ScrollView(context) {
        override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
            parent?.requestDisallowInterceptTouchEvent(event.actionMasked != android.view.MotionEvent.ACTION_UP && event.actionMasked != android.view.MotionEvent.ACTION_CANCEL)
            return super.dispatchTouchEvent(event)
        }
    }
    private class ChoiceListView(context: Context) : ListView(context) {
        override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
            parent?.requestDisallowInterceptTouchEvent(event.actionMasked != android.view.MotionEvent.ACTION_UP && event.actionMasked != android.view.MotionEvent.ACTION_CANCEL)
            return super.dispatchTouchEvent(event)
        }
    }
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
        val style = commandState.presentation(field) ?: return
        val scale = renderScale
        view.setTextColor(Color.parseColor(style.textColor))
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, style.fontPx * scale)
        view.setTypeface(android.graphics.Typeface.DEFAULT, if (style.fontBold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        view.paintFlags = if (style.textUnderline) view.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG else view.paintFlags and android.graphics.Paint.UNDERLINE_TEXT_FLAG.inv()
        view.gravity = Gravity.CENTER_VERTICAL or when (style.alignment) { "left" -> Gravity.START; "right" -> Gravity.END; else -> Gravity.CENTER_HORIZONTAL }
        view.minWidth = 0; view.minHeight = 0; view.minimumWidth = 0; view.minimumHeight = 0
        view.setPadding((4 * scale).toInt(), 0, (4 * scale).toInt(), 0)
        view.includeFontPadding = false
        if (view is Button) view.setAllCaps(false)
        if (view is Switch) view.useCompactSwitch(scale)
        if (view is CheckBox) view.buttonDrawable = compactIndicator(scale, circle = false)
        if (view is CheckedTextView) view.checkMarkDrawable = compactIndicator(scale, circle = field.control != UiControl.MULTISELECT)
        if (field.control in setOf(UiControl.LABEL, UiControl.BUTTON, UiControl.NAVIGATION, UiControl.CHECKBOX, UiControl.SWITCH)) {
            view.setSingleLine(false); view.maxLines = Int.MAX_VALUE; view.ellipsize = null; view.breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
        }
        view.isEnabled = enabled(field)
    }
    private fun windowButton(label: String) = Button(context).apply {
        text = label; textSize = 12f; minWidth = 0; minHeight = 0; minimumWidth = 0; minimumHeight = 0
        includeFontPadding = false; gravity = Gravity.CENTER; setPadding(4.dp(), 0, 4.dp(), 0)
    }
    private fun compactIndicator(scale: Float, circle: Boolean): StateListDrawable {
        val size = ScriptUiControlMetrics.pixels(ScriptUiControlMetrics.INDICATOR, scale)
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
    private fun choicesAdapter(field: UiField, layout: Int, choices: List<String>, emptyChoice: Boolean = false) = object : ArrayAdapter<String>(context, layout, choices) {
        override fun areAllItemsEnabled() = !emptyChoice
        override fun isEnabled(position: Int) = !emptyChoice || position != 0
        private fun compact(view: View, popup: Boolean): View = view.also {
            (it as? TextView)?.let { text ->
                styleText(text, field)
                commandState.presentation(field)?.let { style ->
                    val scale = renderScale
                    val height = ((style.fontPx * 1.8f).coerceAtLeast(20f) * scale).roundToInt().coerceAtLeast(1)
                    val selected = field.control == UiControl.SELECT && !popup
                    text.setSingleLine(false); text.breakStrategy = android.text.Layout.BREAK_STRATEGY_SIMPLE
                    text.maxLines = if (selected) ((style.height - 8) / (style.fontPx * 1.2f)).toInt().coerceAtLeast(1) else Int.MAX_VALUE
                    text.ellipsize = if (selected) android.text.TextUtils.TruncateAt.END else null
                    text.minHeight = if (popup) height.coerceAtLeast(24.dp()) else height
                    val inset = (4 * scale).roundToInt(); text.setPadding(inset, inset, inset, inset)
                    text.layoutParams?.height = if (selected) (style.height * scale).roundToInt().coerceAtLeast(1) else LayoutParams.WRAP_CONTENT
                }
            }
        }
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = compact(super.getView(position, convertView, parent), false)
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
            if (emptyChoice && position == 0) {
                // Hide the nonselectable placeholder from the popup, but retain it in the closed
                // spinner so Android cannot silently manufacture the first real selection.
                return TextView(context).apply {
                    minimumHeight = 0; minHeight = 0; setPadding(0, 0, 0, 0)
                    layoutParams = AbsListView.LayoutParams(LayoutParams.MATCH_PARENT, 0)
                }
            }
            val reusable = convertView?.takeUnless { emptyChoice && it.layoutParams?.height == 0 }
            return compact(super.getDropDownView(position, reusable, parent), true)
        }
    }
}
