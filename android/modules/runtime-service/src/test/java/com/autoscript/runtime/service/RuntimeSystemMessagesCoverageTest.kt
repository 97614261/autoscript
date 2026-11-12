package com.autoscript.runtime.service

import com.autoscript.runtime.api.RuntimeSystemMessages
import org.junit.Assert.*
import org.junit.Test

class RuntimeSystemMessagesCoverageTest {
    @Test fun allAppDiagnosticCodesHaveReadablePersistentSystemMessages() {
        for (code in AppDiagnosticCode.entries) {
            val line = "1790000000000 · App诊断 · ${code.name} [-1]"
            assertNotNull(code.name, RuntimeSystemMessages.description(code.name))
            assertEquals(code.name, RuntimeSystemMessages.isFailure(code.name), RuntimeSystemMessages.diagnosticIsFailure(line))
            assertTrue(RuntimeSystemMessages.readableDiagnostic(line)!!.contains("[-1]"))
        }
    }
}
