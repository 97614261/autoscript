package com.autoscript.studio

import java.io.File
import java.nio.ByteBuffer
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Element

class BrandingResourceTest {
    private val androidNamespace = "http://schemas.android.com/apk/res/android"

    private fun xml(path: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(File("src/main/$path"))

    @Test fun editorAndSystemRuntimeBallShareTheApprovedMark() {
        val shared = File("../../modules/brand-ui/src/main/res/drawable-nodpi/zhigou_brand_mark.png").readBytes()
        assertArrayEquals(shared, File("../../branding/exports/master/zhigou-1024-transparent.png").readBytes())
        assertFalse(File("src/main/res/drawable-nodpi/zhigou_brand_mark.png").exists())
        assertFalse(File("../../modules/runtime-service/src/main/res/drawable-nodpi/zhigou_brand_mark.png").exists())
        assertTrue(File("src/main/java/com/autoscript/studio/ui/StudioOverlayBall.kt").readText().contains(": ZhigouMarkView(context)"))
        assertTrue(File("../../modules/runtime-service/src/main/java/com/autoscript/runtime/service/RuntimeOverlayController.kt").readText().contains("val toggle = ZhigouMarkView(appContext)"))
    }

    @Test fun studioNameAndLauncherResourcesAreWired() {
        val app = xml("AndroidManifest.xml").getElementsByTagName("application").item(0) as Element
        assertEquals("@string/app_name", app.getAttributeNS(androidNamespace, "label"))
        assertEquals("@mipmap/ic_launcher", app.getAttributeNS(androidNamespace, "icon"))
        assertEquals("@mipmap/ic_launcher_round", app.getAttributeNS(androidNamespace, "roundIcon"))
        val name = xml("res/values/branding.xml").getElementsByTagName("string").item(0)
        assertEquals("智构", name.textContent)
    }

    @Test fun legacyAndAdaptiveIconsShareTheSameTransparentMaster() {
        for (name in listOf("ic_launcher", "ic_launcher_round")) {
            val legacy = xml("res/mipmap/$name.xml")
            val bitmap = legacy.getElementsByTagName("bitmap").item(0) as Element
            assertEquals("@drawable/zhigou_brand_mark", bitmap.getAttributeNS(androidNamespace, "src"))
            val adaptive = xml("res/mipmap-anydpi-v26/$name.xml")
            assertEquals("adaptive-icon", adaptive.documentElement.tagName)
            val foreground = adaptive.getElementsByTagName("foreground").item(0) as Element
            assertEquals("@drawable/zhigou_icon_foreground", foreground.getAttributeNS(androidNamespace, "drawable"))
            val themed = xml("res/mipmap-anydpi-v33/$name.xml")
            val monochrome = themed.getElementsByTagName("monochrome").item(0) as Element
            assertEquals("@drawable/zhigou_icon_monochrome", monochrome.getAttributeNS(androidNamespace, "drawable"))
        }
        val foreground = xml("res/drawable/zhigou_icon_foreground.xml")
        assertEquals("20%", foreground.documentElement.getAttributeNS(androidNamespace, "inset"))
        val bitmap = foreground.getElementsByTagName("bitmap").item(0) as Element
        assertEquals("@drawable/zhigou_brand_mark", bitmap.getAttributeNS(androidNamespace, "src"))
        // Check PNG/IHDR directly: Android's compile classpath deliberately excludes Java AWT/ImageIO.
        val mark = File("../../modules/brand-ui/src/main/res/drawable-nodpi/zhigou_brand_mark.png").readBytes()
        assertTrue(mark.size >= 33)
        assertTrue(mark.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map { it.toByte() })
        assertEquals("IHDR", String(mark, 12, 4, Charsets.US_ASCII))
        val width = ByteBuffer.wrap(mark, 16, 4).int
        val height = ByteBuffer.wrap(mark, 20, 4).int
        assertEquals(width, height)
        assertTrue(width in 512..2048)
        assertEquals(6, mark[25].toInt()) // RGBA: preserve the master transparency.
    }

    @Test fun themedMaskAndNightModeKeepLightColoredLauncherIdentity() {
        val mask = xml("res/drawable/zhigou_icon_monochrome.xml").getElementsByTagName("bitmap").item(0) as Element
        assertEquals("@drawable/zhigou_brand_monochrome", mask.getAttributeNS(androidNamespace, "src"))
        val color = xml("res/values-night/branding.xml").getElementsByTagName("color").item(0)
        assertEquals("#FFFFFF", color.textContent)
        val defaultColor = xml("res/values/branding.xml").getElementsByTagName("color").item(0)
        assertEquals(defaultColor.textContent, color.textContent)
    }

    @Test fun genericTerminationNeverClaimsSuccess() {
        for (state in listOf(com.autoscript.core.model.RuntimeEngineState.STOPPED, com.autoscript.core.model.RuntimeEngineState.UNKNOWN)) {
            assertEquals(com.autoscript.brand.ui.BrandState.IDLE, studioBrandState(com.autoscript.core.model.RuntimeConnectionState(engineState = state)))
        }
        assertEquals(com.autoscript.brand.ui.BrandState.RUNNING, studioBrandState(com.autoscript.core.model.RuntimeConnectionState(engineState = com.autoscript.core.model.RuntimeEngineState.RUNNING)))
    }
}
