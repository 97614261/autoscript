package com.autoscript.runtime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LegacyRootCaptureMethodTest {
    class Fixed {
        companion object {
            @JvmStatic fun screenshot(width: Int, height: Int): String = "$width:$height"
        }
    }
    class Instance { fun screenshot(width: Int, height: Int): String = "$width:$height" }
    class WrongSignature {
        companion object {
            @JvmStatic fun screenshot(width: Long, height: Long): String = "$width:$height"
        }
    }

    @Test fun exactStaticMethodReceivesNativeDimensions() {
        val method = legacyRootCaptureMethod(Fixed::class.java, String::class.java)
        assertEquals("0:0", method.invoke(null, 0, 0))
    }

    @Test fun instanceOrWrongReturnTypeCannotBecomePrivilegedCapture() {
        assertThrows(IllegalArgumentException::class.java) { legacyRootCaptureMethod(Instance::class.java, String::class.java) }
        assertThrows(IllegalArgumentException::class.java) { legacyRootCaptureMethod(Fixed::class.java, Any::class.java) }
    }

    @Test fun alternateNumericSignatureIsNotGuessed() {
        assertThrows(NoSuchMethodException::class.java) { legacyRootCaptureMethod(WrongSignature::class.java, String::class.java) }
    }

    @Test fun naturalPixelsRotateBackToLogicalDisplayWithoutScaling() {
        assertEquals(listOf(0f, -90f, -180f, -270f), (0..3).map(::legacyRootCaptureRotationDegrees))
        assertThrows(IllegalArgumentException::class.java) { legacyRootCaptureRotationDegrees(-1) }
        assertThrows(IllegalArgumentException::class.java) { legacyRootCaptureRotationDegrees(4) }
    }
}
