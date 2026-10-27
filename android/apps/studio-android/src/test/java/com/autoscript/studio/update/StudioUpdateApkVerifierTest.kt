package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class StudioUpdateApkVerifierTest {
    private val certificate = "a".repeat(64)
    private val release = StudioUpdateRelease(BuildConfig.VERSION_CODE + 1, "0.2.0", listOf("arm64-v8a"), 100, "b".repeat(64), "", false)
    private val actual = StudioUpdateApkIdentity(BuildConfig.APPLICATION_ID, release.versionCode.toLong(), "0.2.0", 24, setOf("arm64-v8a"), certificate)
    private fun validate(value: StudioUpdateApkIdentity) = validateStudioUpdateApk(value, release, certificate, listOf("arm64-v8a"), 32, BuildConfig.VERSION_CODE)
    @Test fun acceptsOnlyMatchingActualIdentityAndInstalledSigner() { validate(actual) }
    @Test fun rejectsForeignSignerPackageVersionSdkAndAbi() {
        listOf(actual.copy(certificate = "c".repeat(64)), actual.copy(packageName = "other.app"),
            actual.copy(versionCode = BuildConfig.VERSION_CODE.toLong()), actual.copy(versionName = "forged"),
            actual.copy(minSdk = 36), actual.copy(abis = setOf("x86_64")),
            actual.copy(abis = setOf("arm64-v8a", "x86_64"))).forEach {
            try { validate(it); fail("Must reject") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun automaticChecksAreThrottledButClockRollbackDoesNotDisableForever() {
        assertTrue(studioAutoUpdateDue(100, 0))
        assertFalse(studioAutoUpdateDue(101, 100))
        assertTrue(studioAutoUpdateDue(100 + AUTO_UPDATE_INTERVAL_MS, 100))
        assertTrue(studioAutoUpdateDue(99, 100))
    }
}
