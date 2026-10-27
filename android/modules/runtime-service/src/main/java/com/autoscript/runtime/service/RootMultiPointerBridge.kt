package com.autoscript.runtime.service

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

internal data class RootTouchPoint(val id:Int,val x:Int,val y:Int)

/** ASI2: one bounded snapshot per event; pointer indices are not pointer IDs. */
internal fun runRootMultiPointerBridge(input:DataInputStream,output:DataOutputStream,nowMillis:()->Long,
    inject:(Int,List<RootTouchPoint>,Long,Long)->Boolean) {
    val active=linkedMapOf<Int,RootTouchPoint>()
    var downTime=0L;var uncertain=false
    fun send(action:Int):Boolean=runCatching {inject(action,active.values.toList(),downTime,nowMillis())}.getOrDefault(false)
    try {
        output.writeInt(0x41534932);output.flush()
        while(true) {
            val packed=try {input.readInt()} catch(_:EOFException) {break}
            val x=input.readInt();val y=input.readInt()
            if(packed==0x10004) {
                // A restarted bridge has no in-memory history. Supply the complete daemon-owned
                // snapshot rather than falsely acknowledging an empty CANCEL after a timeout.
                require(x in 1..5 && y==0)
                val snapshot=List(x) {RootTouchPoint(input.readInt(),input.readInt(),input.readInt())}
                require(snapshot.map {it.id}.distinct().size==x && snapshot.all {it.id in 0..4 && it.x in 0..32768 && it.y in 0..32768})
                active.clear();snapshot.forEach {active[it.id]=it};if(downTime==0L) downTime=nowMillis()
                val accepted=send(3)
                if(accepted) {active.clear();uncertain=false} else uncertain=true
                output.writeInt(if(accepted) 0 else 3);output.flush();continue
            }
            val multi=packed and 0x10000!=0
            val id=if(multi) packed ushr 8 and 255 else 0
            val action=if(multi) packed and 255 else packed
            val valid=(if(multi) packed and 0x1ff03==packed else action in 0..2) && id in 0..4 && action in 0..3 && x in 0..32768 && y in 0..32768
            var status=1
            if(valid && (!uncertain || action in setOf(1,3))) {
                when(action) {
                    0->if(id !in active && active.size<5) {
                        if(active.isEmpty()) downTime=nowMillis()
                        active[id]=RootTouchPoint(id,x,y) // Record before Binder: rejected result may still have injected DOWN.
                        val index=active.keys.indexOf(id)
                        status=if(send(if(active.size==1) 0 else 5 or (index shl 8))) 0 else 3
                    }
                    2->if(id in active) {active[id]=RootTouchPoint(id,x,y);status=if(send(2)) 0 else 3}
                    1-> {
                        // Fresh bridge may clean a previously uncertain single-pointer injection.
                        if(active.isEmpty() && id==0) {downTime=nowMillis();active[id]=RootTouchPoint(id,x,y)}
                        if(id in active) {
                            val index=active.keys.indexOf(id)
                            status=if(send(if(active.size==1) 1 else 6 or (index shl 8))) {active.remove(id);0} else 3
                        }
                    }
                    3->{status=if(active.isEmpty() || send(3)) {active.clear();uncertain=false;0} else 3}
                }
                if(status==3) uncertain=true
                if(active.isEmpty() && status==0) uncertain=false
            }
            output.writeInt(status);output.flush()
        }
    } catch(_:Exception) {
        // Fixed wire statuses only; never log coordinates, reflective names or request content.
    } finally {if(active.isNotEmpty()) runCatching {send(3)};active.clear()}
}
