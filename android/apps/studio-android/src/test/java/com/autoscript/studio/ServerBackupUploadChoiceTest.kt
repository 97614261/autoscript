package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class ServerBackupUploadChoiceTest {
    private val record = ServerBackupRecord("ab".repeat(16), "encrypted", 64, "cd".repeat(32), 1234)

    @Test fun associationUsesExactProjectAndServerConfiguration() {
        val origin = "http://server.example"
        val key = "ab".repeat(32)
        val association = serverBackupAssociationKey(origin, key, "project-1")
        assertEquals(association, serverBackupAssociationKey(origin, key, "project-1"))
        assertNotEquals(association, serverBackupAssociationKey(origin, key, "project-2"))
        assertNotEquals(association, serverBackupAssociationKey("http://other.example", key, "project-1"))
        assertNotEquals(association, serverBackupAssociationKey(origin, "cd".repeat(32), "project-1"))
        assertFalse(association.contains(key))
    }

    @Test fun newBackupDoesNotUseAnySelectedOverwriteTarget() {
        assertNull(serverBackupUploadTarget(ServerBackupUploadMode.NEW, record.id, listOf(record), false))
    }

    @Test fun overwriteRequiresSupportedServerAndExplicitExistingTarget() {
        assertEquals(record, serverBackupUploadTarget(ServerBackupUploadMode.REPLACE, record.id, listOf(record), true))
        for (target in listOf(null, "missing")) {
            try { serverBackupUploadTarget(ServerBackupUploadMode.REPLACE, target, listOf(record), true); fail("must select target") }
            catch (expected: IllegalStateException) { assertEquals("BACKUP_REPLACE_TARGET_REQUIRED", expected.message) }
        }
        try { serverBackupUploadTarget(ServerBackupUploadMode.REPLACE, record.id, listOf(record), false); fail("must update service") }
        catch (expected: IllegalStateException) { assertEquals("BACKUP_REPLACE_UNSUPPORTED", expected.message) }
    }
}
