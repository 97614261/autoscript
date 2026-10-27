package com.autoscript.runtime.service

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Explicitly user-enabled text submission. Never reads surrounding text, clipboard, storage or network. */
class RuntimeInputMethodService:InputMethodService() {
    override fun onStartInput(attribute:EditorInfo?,restarting:Boolean) {
        super.onStartInput(attribute,restarting)
        RuntimeImeConnection.start(this)
    }
    override fun onFinishInput() {RuntimeImeConnection.finish(this);super.onFinishInput()}
    override fun onDestroy() {RuntimeImeConnection.finish(this);super.onDestroy()}
    override fun onEvaluateFullscreenMode()=false
    override fun onCreateInputView():View=LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL;setBackgroundColor(0xfff1edf9.toInt());setPadding(6,4,6,4)
        addView(TextView(context).apply {text="智构输入法 · 仅提交，不读取或保存内容";textSize=11f;gravity=android.view.Gravity.CENTER},LinearLayout.LayoutParams(-1,28.dp()))
        for(row in listOf("1234567890","qwertyuiop","asdfghjkl","zxcvbnm")) {
            addView(LinearLayout(context).apply {row.forEach {character->addView(Button(context).apply {
                text=character.toString();textSize=12f;minWidth=0;minimumWidth=0;setPadding(0,0,0,0)
                setOnClickListener {currentInputConnection?.commitText(character.toString(),1)}
            },LinearLayout.LayoutParams(0,32.dp(),1f))}},LinearLayout.LayoutParams(-1,32.dp()))
        }
        addView(LinearLayout(context).apply {
            for((label,action) in listOf<Pair<String,()->Unit>>(
                "切换" to {getSystemService(android.view.inputmethod.InputMethodManager::class.java).showInputMethodPicker()},
                "空格" to {currentInputConnection?.commitText(" ",1);Unit},
                "退格" to {currentInputConnection?.deleteSurroundingTextInCodePoints(1,0);Unit},
                "回车" to {currentInputConnection?.commitText("\n",1);Unit},
                "收起" to {requestHideSelf(0)})) {
                addView(Button(context).apply {text=label;textSize=11f;minWidth=0;minimumWidth=0;setPadding(0,0,0,0);setOnClickListener {action()}},LinearLayout.LayoutParams(0,32.dp(),1f))
            }
        },LinearLayout.LayoutParams(-1,32.dp()))
    }
    private fun Int.dp()=(this*resources.displayMetrics.density).toInt()
    internal fun commitProvided(text:String):Boolean=currentInputConnection?.commitText(text,1)==true
}

/** All methods execute on the runner main thread. A focus change invalidates pending script text. */
internal object RuntimeImeConnection {
    private val lease=RuntimeImeLease<RuntimeInputMethodService>()
    fun start(owner:RuntimeInputMethodService)=lease.start(owner)
    fun finish(owner:RuntimeInputMethodService)=lease.finish(owner)
    fun ticket():Any?=lease.ticket()
    fun connected()=lease.ticket()!=null
    fun commit(expected:Any?,text:String)=lease.commit(expected,text) {owner,value->owner.commitProvided(value)}
}
