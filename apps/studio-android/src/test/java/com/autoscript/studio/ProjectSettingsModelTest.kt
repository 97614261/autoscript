package com.autoscript.studio

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ProjectSettingsModelTest {
    @Test
    fun capabilityChoicesPreserveDeclaredUnknownCapabilitiesAndDeduplicate() {
        val options = settingsCapabilityOptions(
            listOf("vision.pixel", "core.task", "vision.pixel"),
            listOf("custom.future", "core.task"),
        )
        assertEquals(listOf("core.task", "custom.future", "vision.pixel"), options)
        assertEquals("custom.future", settingsCapabilityTitle("custom.future"))
        assertEquals("按键与触摸", settingsCapabilityTitle("input.basic"))
    }

    @Test
    fun resourceFiltersPreserveOrderIdentityAndOriginalList() {
        val image = resource("image", "assets/images/a.png")
        val dictionary = resource("glyphDictionary", "dictionaries/test.asglyph")
        val other = resource("future", "assets/other.dat")
        val resources = listOf(image, dictionary, other)
        assertEquals(resources, filterSettingsResources(resources, null))
        assertEquals(listOf(image), filterSettingsResources(resources, "image"))
        assertEquals(listOf(dictionary), filterSettingsResources(resources, "glyphDictionary"))
        assertEquals(emptyList<JsonObject>(), filterSettingsResources(resources, "missing"))
        assertSame(image, filterSettingsResources(resources, "image").single())
        assertEquals(3, resources.size)
    }

    @Test
    fun allThreeSettingsSectionsHaveDistinctVisibleTitles() {
        assertEquals(listOf("项目资源", "脚本能力", "运行界面"), ProjectSettingsPage.entries.map { it.title })
    }

    private fun resource(kind: String, path: String) = JsonObject().apply {
        addProperty("kind", kind)
        addProperty("path", path)
    }
}
