package com.autoscript.runtime.service

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Opt-in test APK only. No reads from InputConnection, storage, clipboard or network. */
class QaInputMethodService : InputMethodService() {
    private var allowed = false

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        allowed = attribute?.packageName in setOf("com.zhigou.kaifaban", "com.autoscript.vision.acceptance")
        if (!allowed) requestHideSelf(0)
    }

    override fun onEvaluateFullscreenMode() = false
    override fun onEvaluateInputViewShown() = allowed

    override fun onCreateInputView(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        contentDescription = "QA键盘面板"
        setBackgroundColor(0xffe8edf7.toInt())
        val density = resources.displayMetrics.density
        val insetHeight = (176 * density).toInt().coerceAtLeast(176)
        minimumHeight = insetHeight
        layoutParams = android.view.ViewGroup.LayoutParams(-1, insetHeight)
        addView(TextView(context).apply {
            text = "智构 QA 测试键盘 · 不读取或保存输入"; textSize = 12f
            gravity = android.view.Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(Button(context).apply {
            text = "输入 QA"; contentDescription = "QA键盘输入"
            setOnClickListener { if (allowed) currentInputConnection?.commitText("QA", 1) }
        }, LinearLayout.LayoutParams(-1, (48 * density).toInt()))
        addView(Button(context).apply {
            text = "收起键盘"; contentDescription = "QA键盘收起"
            setOnClickListener { requestHideSelf(0) }
        }, LinearLayout.LayoutParams(-1, (40 * density).toInt()))
    }

    override fun onFinishInput() {
        allowed = false
        super.onFinishInput()
    }
}
