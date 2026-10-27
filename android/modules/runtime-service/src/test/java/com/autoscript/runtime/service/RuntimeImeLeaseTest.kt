package com.autoscript.runtime.service

import org.junit.Assert.*
import org.junit.Test

class RuntimeImeLeaseTest {
    @Test fun focusChangesAndLateFinishesCannotCommitIntoAnotherEditor() {
        val lease=RuntimeImeLease<Any>();val first=Any();val second=Any()
        lease.start(first);val old=lease.ticket();lease.start(second);val current=lease.ticket();lease.finish(first)
        assertSame(current,lease.ticket());var sent=false
        assertThrows(IllegalStateException::class.java) {lease.commit(old,"secret") {_,_->sent=true;true}}
        assertFalse(sent);assertTrue(lease.commit(current,"A1b2") {owner,text->assertSame(second,owner);assertEquals("A1b2",text);true})
        lease.finish(second);assertNull(lease.ticket())
        assertThrows(IllegalStateException::class.java) {lease.commit(current,"late") {_,_->fail("stale");true}}
    }
    @Test fun byteBoundsAndNullsAreRejectedBeforeCallingInputConnection() {
        val lease=RuntimeImeLease<Any>();lease.start(Any())
        for(value in listOf("字".repeat(1366),"a\u0000b")) assertThrows(IllegalArgumentException::class.java) {lease.commit(lease.ticket(),value) {_,_->fail("invalid");true}}
        assertFalse(lease.commit(lease.ticket(),"A") {_,_->false})
    }
}
