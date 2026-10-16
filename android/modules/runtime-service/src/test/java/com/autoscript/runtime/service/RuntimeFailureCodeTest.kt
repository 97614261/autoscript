package com.autoscript.runtime.service

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeFailureCodeTest {
    @Test fun onlyLeadingWhitelistedCodesArePersisted() {
        assertEquals("ROOT_STOP_CLEANUP_FAILED", runtimeFailureCode("ROOT_STOP_CLEANUP_FAILED: private details"))
        assertEquals("LUA_RUNTIME_ERROR", runtimeFailureCode("LUA_RUNTIME_ERROR: private source: ROOT_STOP_CLEANUP_FAILED"))
        assertEquals("ENGINE_FAILURE", runtimeFailureCode("private source ROOT_STOP_CLEANUP_FAILED: secret"))
        assertEquals("ENGINE_FAILURE", runtimeFailureCode(null))
        assertEquals("ENGINE_FAILURE", runtimeFailureCode(""))
    }
}
