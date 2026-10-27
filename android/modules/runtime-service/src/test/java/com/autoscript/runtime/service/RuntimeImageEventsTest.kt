package com.autoscript.runtime.service

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class RuntimeImageEventsTest {
    @Test fun completeGesturesAreOrderedAndOverflowIsExplicit() {
        val events=RuntimeImageEvents()
        repeat(65) {events.add(if(it%2==0) "click" else "drag",it,it,1,2)}
        val first=JsonParser.parseString(requireNotNull(events.read())).asJsonObject
        assertEquals(2L,first["sequence"].asLong);assertEquals(1L,first["dropped"].asLong)
        assertEquals("drag",first["kind"].asString);assertEquals(1,first["x"].asInt)
        repeat(63) {assertNotNull(events.read())};assertNull(events.read())
        val timeout=JsonParser.parseString(events.terminal("timeout")).asJsonObject
        assertEquals(65L,timeout["sequence"].asLong);assertEquals("timeout",timeout["kind"].asString)
        assertEquals("closed",JsonParser.parseString(events.terminal("closed")).asJsonObject["kind"].asString)
    }
    @Test fun invalidEventsCannotBecomeCallbacks() {
        assertThrows(IllegalArgumentException::class.java) {RuntimeImageEvents().add("execute",0,0,0,0)}
        assertThrows(IllegalArgumentException::class.java) {RuntimeImageEvents().add("click",-1,0,0,0)}
    }
}
