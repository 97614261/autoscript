package com.autoscript.runtime.service

import java.io.*
import org.junit.Assert.*
import org.junit.Test

class RootMultiPointerBridgeTest {
    @Test fun ordinaryTapSequenceIsRepeatedWithoutRetainingPointersOrChangingDownTimeWithinTap() {
        val (_,events)=run(List(100) { listOf(listOf(0,10,20),listOf(1,10,20)) }.flatten())
        assertEquals(200,events.size)
        events.chunked(2).forEach { pair ->
            assertEquals(listOf(0,1),pair.map { it.action })
            assertEquals(1,pair.map { it.down }.distinct().size)
            assertEquals(listOf(RootTouchPoint(0,10,20)),pair.last().points)
        }
    }
    private data class Event(val action:Int,val points:List<RootTouchPoint>,val down:Long)
    private fun run(actions:List<List<Int>>,accept:(Int)->Boolean={true}):Pair<List<Int>,List<Event>> {
        val bytes=ByteArrayOutputStream();DataOutputStream(bytes).use {out->actions.flatten().forEach(out::writeInt)}
        val output=ByteArrayOutputStream();val events=mutableListOf<Event>();var time=10L
        runRootMultiPointerBridge(DataInputStream(ByteArrayInputStream(bytes.toByteArray())),DataOutputStream(output),{++time}) {action,points,down,_->
            events.add(Event(action,points,down));accept(action)
        }
        val replies=DataInputStream(ByteArrayInputStream(output.toByteArray()));assertEquals(0x41534932,replies.readInt())
        return buildList {while(replies.available()>0) add(replies.readInt())} to events
    }
    private fun command(action:Int,id:Int,x:Int=10,y:Int=20)=listOf(0x10000 or (id shl 8) or action,x,y)
    @Test fun twoPointersPreserveIdsIndicesCoordinatesAndSharedDownTime() {
        val (statuses,events)=run(listOf(command(0,4),command(0,1,30,40),command(2,4,50,60),command(1,4),command(1,1)))
        assertEquals(List(5){0},statuses)
        assertEquals(listOf(0,261,2,6,1),events.map {it.action})
        assertEquals(listOf(4,1),events[1].points.map {it.id})
        assertEquals(50,events[2].points[0].x);assertEquals(30,events[2].points[1].x)
        assertEquals(1,events.map {it.down}.distinct().size)
        assertEquals(listOf(1),events.last().points.map {it.id})
    }
    @Test fun invalidIdsDuplicateDownAndMoveBeforeDownDoNotInject() {
        val (statuses,events)=run(listOf(command(0,5),command(2,1),command(0,1),command(0,1),command(3,0)))
        assertEquals(listOf(1,1,0,1,0),statuses);assertEquals(listOf(0,3),events.map {it.action})
    }
    @Test fun eofAndUncertainDownCancelEveryHeldPointer() {
        val (_,events)=run(listOf(command(0,0),command(0,1)))
        assertEquals(3,events.last().action);assertEquals(listOf(0,1),events.last().points.map {it.id})
        val (statuses,uncertain)=run(listOf(command(0,1),command(0,2))) {it!=0}
        assertEquals(listOf(3,1),statuses);assertEquals(listOf(0,3),uncertain.map {it.action})
    }
    @Test fun restartedBridgeUsesExplicitCompleteCancellationSnapshot() {
        val (statuses,events)=run(listOf(listOf(0x10004,2,0,1,10,20,4,30,40)))
        assertEquals(listOf(0),statuses);assertEquals(1,events.size);assertEquals(3,events.single().action)
        assertEquals(listOf(1,4),events.single().points.map {it.id})
    }
}
