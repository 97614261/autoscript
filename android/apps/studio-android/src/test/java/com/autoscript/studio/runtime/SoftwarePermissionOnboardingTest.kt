package com.autoscript.studio

import org.junit.Assert.assertEquals
import org.junit.Test

class SoftwarePermissionOnboardingTest {
    @Test fun overlayIsTheFirstActionWhenItIsMissing() {
        assertEquals(InitialPermissionAction.OVERLAY, initialPermissionAction(false, false, 35))
        assertEquals(InitialPermissionAction.OVERLAY, initialPermissionAction(false, true, 35))
    }

    @Test fun notificationsAreRequestedOnlyAfterOverlayOnAndroid13OrNewer() {
        assertEquals(InitialPermissionAction.NOTIFICATIONS, initialPermissionAction(true, false, 33))
        assertEquals(InitialPermissionAction.NOTIFICATIONS, initialPermissionAction(true, false, 35))
        assertEquals(InitialPermissionAction.NONE, initialPermissionAction(true, false, 32))
    }

    @Test fun completedBasicPermissionsDoNotOpenAStartupRequest() {
        assertEquals(InitialPermissionAction.NONE, initialPermissionAction(true, true, 35))
        assertEquals(InitialPermissionAction.NONE, initialPermissionAction(true, true, 24))
    }
}
