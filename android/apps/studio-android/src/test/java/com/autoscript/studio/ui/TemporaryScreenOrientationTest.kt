package com.autoscript.studio

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class TemporaryScreenOrientationTest {
    @Test fun unspecifiedRestoresTheUserRotationPolicy() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER,
            restoredScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED))
        for (policy in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_USER,
                ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR, ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE)) {
            assertEquals(policy, restoredScreenOrientation(policy))
        }
    }

    @Test fun returningAndDisposingRestoreOnlyOnce() {
        var direction = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val writes = mutableListOf<Int>()
        val lease = TemporaryScreenOrientation({ direction }, { direction = it; writes += it }, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        lease.acquire()
        lease.acquire()
        lease.release()
        lease.release()
        assertEquals(listOf(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_USER), writes)
    }

    @Test fun disposalDoesNotOverrideAnotherPageOrientation() {
        var direction = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val lease = TemporaryScreenOrientation({ direction }, { direction = it }, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        lease.acquire()
        direction = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        lease.release()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR, direction)
    }
}
