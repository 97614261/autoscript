package com.autoscript.studio

import com.autoscript.project.store.ProjectVariableType as Type
import com.autoscript.studio.generated.BlockContract
import com.google.gson.JsonObject

internal fun RecognitionWaitMode.usesFreshFrames()=this in setOf(RecognitionWaitMode.STABLE,RecognitionWaitMode.REGION_CHANGED,RecognitionWaitMode.REGION_STABLE)

/** Visible ordinary blocks, with one live baseline lease and no implicit work variables. */
internal fun stableRecognitionBlocks(contract:BlockContract,arguments:JsonObject,draft:RecognitionWaitDraft,
    variables:List<CalculationVariable>):List<VisualBlockTree> {
    require(draft.mode.usesFreshFrames())
    require(draft.confirmFrames) {"请明确确认两份专用截图变量允许覆盖和释放"}
    val regionOnly=draft.mode!=RecognitionWaitMode.STABLE
    val ocr=contract.kind.startsWith("ocr.") && !regionOnly
    val timeout=requireNotNull(draft.timeout.toIntOrNull());val interval=requireNotNull(draft.interval.toIntOrNull())
    val required=requireNotNull(draft.consecutive.toIntOrNull());val drift=requireNotNull(draft.driftPx.toIntOrNull())
    val ratio=requireNotNull(draft.changePermille.toIntOrNull());val tolerance=requireNotNull(draft.changeTolerance.toIntOrNull())
    require(timeout in 1..600_000 && interval in 1..60_000 && required in 1..100 && drift in 0..32768 && ratio in 0..1000 && tolerance in 0..255) {"期限/间隔、连续次数1–100、偏移0–32768px、变化率0–1000‰、容差0–255"}
    if(draft.mode==RecognitionWaitMode.REGION_CHANGED) require(ratio>0) {"等待变化阈值须大于0‰"}
    val slots=recognitionWaitSlots(contract.kind,draft)
    val references=recognitionWaitReferences(arguments)
    require(slots.map {it.value}.distinct().size==slots.size && slots.all {slot ->slot.value !in references && variables.any {it.name==slot.value && it.type==slot.type}}) {"工作变量须独立声明且不能覆盖识别输入/输出"}
    val frame=arguments["frameVariable"]?.asString.orEmpty()
    require(variables.any {it.name==frame && it.type==Type.IMAGE}) {"请选择独立图像变量作为当前截图暂存"}
    require(frame!=draft.previousFrame)
    val found=if(ocr) draft.match else arguments["foundVariable"]?.asString.orEmpty()
    val region=arguments["region"]?.asJsonObject ?: error("需要明确识别范围")
    require(listOf("leftVariable","topVariable","rightVariable","bottomVariable").all {arguments[it]?.asString.isNullOrEmpty()}) {"稳定组合使用固定范围；动态范围请手工组合，不能隐藏冻结"}
    if(!regionOnly && !ocr) require(variables.any {it.name==found && it.type==Type.INTEGER})
    fun json(vararg pairs:Pair<String,Any>)=JsonObject().apply {pairs.forEach {(key,value)->when(value) {
        is Number->addProperty(key,value);is Boolean->addProperty(key,value);else->addProperty(key,value.toString())}}}
    fun block(kind:String,args:JsonObject=JsonObject(),children:Map<String,List<VisualBlockTree>> = emptyMap())=
        VisualBlockTree(requireNotNull(BlockCatalog.find(kind)),args,children)
    fun set(name:String,expression:String)=block("variable.calculate",json("name" to name,"expression" to expression))
    fun branch(variable:String,operator:String,value:Any,yes:List<VisualBlockTree>,no:List<VisualBlockTree> = emptyList(),valueVariable:String?=null)=
        block("control.if",json("variable" to variable,"operator" to operator,"value" to value).apply {valueVariable?.let {addProperty("valueVariable",it)}},mapOf("then" to yes,"else" to no))
    fun read(field:String,target:String)=block("vision.readresult",json("sourceVariable" to draft.difference,"shape" to "FRAME_DIFF","index" to 0,"field" to field,"resultVariable" to target))
    fun capture(target:String)=block("screen.capture",json("resultVariable" to target))
    fun release(target:String)=block("screen.release",json("frameVariable" to target))
    fun now()=block("system.elapsed",json("resultVariable" to draft.clock))
    fun elapsed()=set(draft.elapsed,"${draft.clock} - ${draft.start}")
    val stop=block("control.break")
    val deadline=branch(draft.elapsed,"greaterOrEqual",timeout,listOf(stop))
    val compare=json("frameVariable" to frame,"previousFrameVariable" to draft.previousFrame,"tolerance" to tolerance,"resultVariable" to draft.difference)
    for(key in listOf("left","top","right","bottom")) compare.add(key,if(regionOnly) region[key].deepCopy() else com.google.gson.JsonPrimitive(if(key in setOf("right","bottom")) 1 else 0))
    val increment=listOf(set(draft.streak,"${draft.streak} + 1"))
    val clear=listOf(set(draft.streak,"0"))
    val recognition=arguments.deepCopy().apply {
        if(recognitionUsesOwnCapture(contract.kind)) addProperty("autoCapture",false) else remove("autoCapture")
        if(contract.kind=="vision.findimage") {addProperty("frequency",1);addProperty("successAction","none")}
    }
    val inspect:List<VisualBlockTree> = if(regionOnly) listOf(read("ratioPermille",draft.distance),
        branch(draft.distance,if(draft.mode==RecognitionWaitMode.REGION_STABLE) "lessOrEqual" else "greaterOrEqual",ratio,increment,clear))
    else if(ocr) {
        val text=arguments["textVariable"]?.asString.orEmpty()
        require(variables.any {it.name==text && it.type==Type.STRING})
        require(draft.targetText.toByteArray(Charsets.UTF_8).size<=256)
        val valid=if(draft.targetText.isEmpty()) listOf(block("text.length",json("text" to "","textVariable" to text,"resultVariable" to found)),
            branch(found,"greaterThan",0,listOf(set(found,"1")),listOf(set(found,"0"))))
        else if(draft.exactText) listOf(branch(text,"equals",draft.targetText,listOf(set(found,"1")),listOf(set(found,"0"))))
        else listOf(block("text.contains",json("text" to "","textVariable" to text,"needle" to draft.targetText,"resultVariable" to found)))
        val predicate=if(contract.kind=="ocr.glyph") {
            val coverage=arguments["coverageVariable"]?.asString.orEmpty()
            require(coverage!=arguments["scoreVariable"]?.asString && variables.any {it.name==coverage && it.type==Type.INTEGER}) {"稳定字库识别须独立覆盖率输出"}
            listOf(branch(coverage,"equals",1000,valid,listOf(set(found,"0"))))
        } else valid
        listOf(VisualBlockTree(contract,recognition))+predicate+listOf(branch(found,"equals",1,
            listOf(branch(draft.streak,"greaterThan",0,listOf(branch(text,"equals","",increment,listOf(set(draft.streak,"1")),draft.previousText)),listOf(set(draft.streak,"1"))),
                block("variable.copy",json("name" to draft.previousText,"sourceName" to text))),clear))
    } else {
        val x=arguments["xVariable"]?.asString.orEmpty();val y=arguments["yVariable"]?.asString.orEmpty()
        require(listOf(x,y).all {name->variables.any {it.name==name && it.type==Type.INTEGER}})
        val distance="($x - ${draft.previousX}) * ($x - ${draft.previousX}) + ($y - ${draft.previousY}) * ($y - ${draft.previousY})"
        val position=listOf(branch(draft.streak,"greaterThan",0,
            listOf(set(draft.distance,distance),branch(draft.distance,"lessOrEqual",drift.toLong()*drift,increment,listOf(set(draft.streak,"1")))),listOf(set(draft.streak,"1"))),
            set(draft.previousX,x),set(draft.previousY,y))
        listOf(VisualBlockTree(contract,recognition),branch(found,"equals",1,position,clear))
    }
    val endCheck=listOf(now(),elapsed(),deadline,branch(draft.streak,"greaterOrEqual",required,listOf(set(draft.outcome,"1"),stop)))
    val work=listOf(now(),elapsed(),deadline,set(draft.clock,"$timeout - ${draft.elapsed}"),
        branch(draft.clock,"greaterThan",interval,listOf(set(draft.clock,interval.toString()))),
        block("task.sleep",json("milliseconds" to 0,"millisecondsVariable" to draft.clock,"exactTiming" to true)),
        now(),elapsed(),deadline,capture(frame),set(draft.attempt,"${draft.attempt} + 1"),block("screen.compareframes",compare),read("fresh",draft.fresh),
        release(draft.previousFrame),block("variable.copy",json("name" to draft.previousFrame,"sourceName" to frame)),
        branch(draft.fresh,"equals",1,inspect))+endCheck
    return listOf(set(draft.outcome,"0"),set(draft.attempt,"0"),set(draft.streak,"0"))+
        (if(regionOnly) emptyList() else listOf(set(draft.previousX,"0"),set(draft.previousY,"0")))+listOf(
        block("system.elapsed",json("resultVariable" to draft.start)),capture(draft.previousFrame),
        block("control.cycle",json("yieldMs" to 1,"durationMs" to timeout),mapOf("body" to work)),release(draft.previousFrame),now(),elapsed(),
        branch(draft.outcome,"equals",1,emptyList()))
}
