package com.autoscript.runtime.service

import com.google.gson.JsonObject
import java.util.ArrayDeque

/** Main-thread queue: retain latest 64 complete gestures, never buffer move samples. */
internal class RuntimeImageEvents {
    data class Event(val kind:String,val x:Int,val y:Int,val localX:Int,val localY:Int,val sequence:Long)
    private val events=ArrayDeque<Event>()
    private var sequence=0L
    private var dropped=0L
    fun add(kind:String,x:Int,y:Int,localX:Int,localY:Int) {
        require(kind in setOf("click","drag") && listOf(x,y,localX,localY).all {it in 0..32768})
        check(sequence<Long.MAX_VALUE);sequence++
        if(events.size==64) {events.removeFirst();dropped=(dropped+1).coerceAtMost(Long.MAX_VALUE-1)}
        events.addLast(Event(kind,x,y,localX,localY,sequence))
    }
    fun read():String?=events.pollFirst()?.let(::encode)
    fun terminal(kind:String):String {require(kind in setOf("timeout","closed"));return encode(Event(kind,0,0,0,0,sequence))}
    private fun encode(event:Event)=JsonObject().apply {
        addProperty("kind",event.kind);addProperty("x",event.x);addProperty("y",event.y)
        addProperty("localX",event.localX);addProperty("localY",event.localY)
        addProperty("sequence",event.sequence);addProperty("dropped",dropped)
    }.toString()
}
