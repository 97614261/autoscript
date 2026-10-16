package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class ScriptLaunchSessionsTest {
    @Test fun `configuration cannot replace an editor session even for the same project`() {
        assertFalse(canOpenScriptLaunch("p1", null, "p1"))
        assertFalse(canOpenScriptLaunch("p1", null, "p2"))
    }
    @Test fun `reopening same configuration is allowed but switching projects requires closure`() {
        assertTrue(canOpenScriptLaunch(null, null, "p1"))
        assertTrue(canOpenScriptLaunch(null, "p1", "p1"))
        assertFalse(canOpenScriptLaunch(null, "p1", "p2"))
    }
}
