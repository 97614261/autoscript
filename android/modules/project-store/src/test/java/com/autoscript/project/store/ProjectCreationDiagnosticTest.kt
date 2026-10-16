package com.autoscript.project.store

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectCreationDiagnosticTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun summaryOmitsExceptionMessagesPathsAndUntrustedFrames() {
        val failure = IOException("private-project /data/private/secret.lua token=hidden")
        failure.stackTrace = arrayOf(
            StackTraceElement("private.project.secret", "hidden", "secret.lua", 8),
            StackTraceElement("com.autoscript.project.store.ProjectStore", "writeAndSync", "/secret/path.kt", 42),
        )
        val diagnostic = projectCreationDiagnostic(ProjectCreationStage.WRITE_MANIFEST, failure)
        assertEquals("IOException", diagnostic.failureType)
        assertEquals("com.autoscript.project.store.ProjectStore.writeAndSync:42", diagnostic.origin)
        assertFalse(diagnostic.summary.contains("private-project"))
        assertFalse(diagnostic.summary.contains("secret"))
        assertFalse(diagnostic.summary.contains("hidden"))
        assertNull(diagnostic.missingSymbol)
    }

    @Test fun linkageErrorExtractsOnlyMethodSymbolNotTheApkPath() {
        val failure = NoSuchMethodError("No virtual method nextToken()V in class Lexample/Api; (declaration appears in /private/example.apk)")
        val diagnostic = projectCreationDiagnostic(ProjectCreationStage.REOPEN, IllegalStateException("private name", failure))
        assertEquals("NoSuchMethodError", diagnostic.failureType)
        assertEquals("symbol=nextToken", diagnostic.missingSymbol)
        assertFalse(diagnostic.summary.contains("/private"))
        assertFalse(diagnostic.summary.contains("private name"))
    }

    @Test fun causeCyclesRemainBounded() {
        val first = IllegalStateException("one")
        val second = IOException("two", first)
        first.initCause(second)
        val diagnostic = projectCreationDiagnostic(ProjectCreationStage.MANIFEST, first)
        assertEquals("IOException", diagnostic.failureType)
    }

    @Test fun failureBeforeCommitCleansOnlyOwnedStagingAndReportsPhase() {
        val root = temporary.newFolder("projects")
        val sentinel = File(root, "keep.txt").apply { writeText("keep") }
        val store = ProjectStore(root, clock = { throw IOException("secret location") }, idFactory = { "project-diag" })
        val failure = assertThrows(ProjectStoreException::class.java) { store.createProject("private name", ProjectSourceMode.VISUAL) }
        assertEquals(ProjectCreationStage.WRITE_STATE, failure.creationDiagnostic?.stage)
        assertFalse(File(root, ".project-diag.creating").exists())
        assertEquals("keep", sentinel.readText())
        assertFalse(failure.message.orEmpty().contains("private name"))
    }

    @Test fun failureAfterCommitPreservesProjectAndReportsReopenPhase() {
        val root = temporary.newFolder("committed")
        var calls = 0
        val store = ProjectStore(root, clock = {
            if (++calls == 2) throw NoSuchMethodError("No static method unsupported()V /private")
            123L
        }, idFactory = { "project-committed" })
        val failure = assertThrows(ProjectStoreException::class.java) { store.createProject("private name", ProjectSourceMode.VISUAL) }
        assertEquals(ProjectCreationStage.REOPEN, failure.creationDiagnostic?.stage)
        assertTrue(File(root, "project-committed/project.json").isFile)
        assertEquals(1, ProjectStore(root).listProjects().size)
        assertFalse(File(root, ".project-committed.creating").exists())
    }
}
