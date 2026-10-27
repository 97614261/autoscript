package com.autoscript.studio

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class StudioUpdateManifestTest {
    private fun release(abi: String = "arm64-v8a", code: Int = 3) = JsonObject().apply {
        addProperty("owner", "zhigou-docs-download-v1"); addProperty("available", true)
        addProperty("applicationId", "com.zhigou.kaifaban"); addProperty("versionName", "0.1.2")
        addProperty("versionCode", code); addProperty("bytes", 65000000); addProperty("sha256", "a".repeat(64))
        add("abis", JsonArray().apply { add(abi) }); addProperty("minSdk", 24)
        addProperty("url", "/downloads/zhigou-0.1.2-dev-${"a".repeat(12)}.apk")
        addProperty("checksumUrl", "/downloads/zhigou-0.1.2-dev-${"a".repeat(12)}.apk.sha256")
        addProperty("variant", "release"); addProperty("profile", "full")
        addProperty("status", "optimized-candidate-unverified"); addProperty("description", "测试候选")
    }
    private fun select(json: JsonObject, abis: List<String> = listOf("arm64-v8a")) =
        selectStudioUpdate(json.toString().toByteArray(), abis, 32)
    private fun rejected(block: () -> Unit) { try { block(); fail("Must reject") } catch (_: Exception) { } }

    @Test fun selectsDeviceAbiFromCurrentAlternativesButNeverHistory() {
        val root = release().apply {
            add("alternatives", JsonArray().apply { add(release("x86_64")) })
            add("history", JsonArray().apply { add(release("arm64-v8a", 99)) })
        }
        assertEquals(listOf("x86_64"), select(root, listOf("x86_64", "arm64-v8a"))!!.abis)
        assertEquals(3, select(root)!!.versionCode)
        assertNull(select(root, listOf("armeabi-v7a")))
    }

    @Test fun comparesNumericVersionAndDoesNotUpgradeSameCodeBuilds() {
        val item = select(release())!!
        assertEquals(StudioUpdateRelation.NEWER, studioUpdateRelation(item, 2))
        assertEquals(StudioUpdateRelation.SAME_VERSION, studioUpdateRelation(item, 3))
        assertEquals(StudioUpdateRelation.SITE_OLDER, studioUpdateRelation(item, 4))
        assertTrue(item.unverifiedCandidate)
    }

    @Test fun boundsManifestAndRejectsDuplicatesAndDeepInputs() {
        rejected { selectStudioUpdate(ByteArray(65537), listOf("arm64-v8a"), 32) }
        rejected { selectStudioUpdate("{\"available\":false,\"available\":true}".toByteArray(), emptyList(), 32) }
        rejected { selectStudioUpdate(("[".repeat(10) + "0" + "]".repeat(10)).toByteArray(), emptyList(), 32) }
        rejected { selectStudioUpdate((release().toString() + "{}").toByteArray(), emptyList(), 32) }
    }

    @Test fun rejectsForeignAppPathsSizeAndAbiCollisions() {
        rejected { select(release().apply { addProperty("applicationId", "other.app") }) }
        rejected { select(release().apply { addProperty("url", "https://untrusted.invalid/update.apk") }) }
        rejected { select(release().apply { addProperty("bytes", 2L * 1024 * 1024 * 1024) }) }
        rejected { select(release().apply { add("alternatives", JsonArray().apply { add(release()) }) }) }
        rejected { select(release().apply { add("alternatives", JsonArray().apply { add(release("x86_64", 4)) }) }) }
    }

    @Test fun unpublishedOrIncompatibleSdkIsNotAnUpdate() {
        assertNull(selectStudioUpdate("{\"available\":false}".toByteArray(), listOf("arm64-v8a"), 32))
        assertNull(select(release().apply { addProperty("minSdk", 36) }))
    }

    @Test fun siteOnlyAllowsExplicitHttpOrHttpsOriginsAndFixedPaths() {
        val site = StudioUpdateSite.parse("https://updates.example.test/")
        assertTrue(site.encrypted)
        assertEquals("https://updates.example.test/downloads/release.json", site.manifest.toASCIIString())
        assertEquals("https://updates.example.test/download", site.downloadPage.toASCIIString())
        assertFalse(StudioUpdateSite.parse("http://127.0.0.1:8080").encrypted)
        listOf("file:///tmp/a", "https://user:pass@example.test", "https://example.test/path",
            "https://example.test?q=1", "https://example.test/#fragment", "javascript:alert(1)").forEach {
            rejected { StudioUpdateSite.parse(it) }
        }
    }
}
