package com.autoscript.runtime.service

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeAppDiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun captureFailuresKeepOnlyRequestOwnedFixedCodes() {
        for(code in AppDiagnosticCode.entries.filter {it.name.startsWith("CAPTURE_") && it !in setOf(AppDiagnosticCode.CAPTURE_READY,AppDiagnosticCode.CAPTURE_REQUESTED)}) {
            assertEquals(code,capturePreviewFailureCode(IllegalStateException(code.name)))
        }
        for(raw in listOf("CAPTURE_READY","CAPTURE_REQUESTED","CAPTURE_TRANSPORT /data/private secret","unknown")) {
            assertEquals(AppDiagnosticCode.CAPTURE_REQUEST_FAILED,capturePreviewFailureCode(IllegalStateException(raw)))
        }
        assertEquals(AppDiagnosticCode.CAPTURE_BACKEND_FAILED,capturePreviewFailureCode(RuntimeException("secret")))
        assertEquals(AppDiagnosticCode.CAPTURE_BACKEND_FAILED,capturePreviewFailureCode(null))
    }

    @Test fun persistsWithoutAnyScriptRunAndRotatesOnlyOwnedFiles() {
        val directory = temporary.newFolder()
        val old = File(directory, "app.log").apply { writeText("x".repeat(65536)) }
        val unrelated = File(directory, "keep.txt").apply { writeText("unchanged") }
        val store = RuntimeAppDiagnostics(directory)
        store.record(AppDiagnosticCode.ROOT_PROCESS_EXITED, 126)
        store.record(AppDiagnosticCode.CAPTURE_BACKEND_FAILED)
        store.close(); assertTrue(store.awaitClosed())
        assertFalse(store.incomplete)
        assertTrue(old.readText().contains("CAPTURE_BACKEND_FAILED"))
        assertEquals(65536, File(directory, "app.previous.log").length().toInt())
        assertEquals("unchanged", unrelated.readText())
    }

    @Test fun classifiesButNeverPersistsPrivateProcessOutput() {
        val secret = "private-script /data/private/path: Permission denied token=secret"
        val category = rootProcessFailureCode(secret)!!
        assertEquals(AppDiagnosticCode.ROOT_PROCESS_PERMISSION_DENIED, category)
        val logged = mutableListOf<String>()
        val directory = temporary.newFolder()
        val store = RuntimeAppDiagnostics(directory, logged::add)
        store.record(category, 1); store.close(); assertTrue(store.awaitClosed())
        val saved = File(directory, "app.log").readText()
        for (privateText in listOf("private-script", "/data/", "secret", "Permission denied")) {
            assertFalse(saved.contains(privateText)); assertTrue(logged.none { it.contains(privateText) })
        }
        assertEquals(AppDiagnosticCode.ROOT_PROCESS_LINK_FAILED, rootProcessFailureCode("CANNOT LINK EXECUTABLE"))
        assertEquals(AppDiagnosticCode.ROOT_PROCESS_COMMAND_MISSING, rootProcessFailureCode("su: not found"))
        assertEquals(AppDiagnosticCode.ROOT_IDLE_RELEASED, rootProcessFailureCode("daemon connection idle timeout"))
        assertNull(rootProcessFailureCode("arbitrary output"))
    }

    @Test fun previousEntriesAreBoundedAndRawLinesAreNotRestored() {
        val directory = temporary.newFolder()
        File(directory, "app.log").writeText("private script\n123 · App诊断 · ROOT_START [0]\n")
        val store = RuntimeAppDiagnostics(directory)
        store.loadPrevious(); store.close(); assertTrue(store.awaitClosed())
        assertEquals(listOf("123 · App诊断 · ROOT_START [0]"), store.recent())
    }
}
