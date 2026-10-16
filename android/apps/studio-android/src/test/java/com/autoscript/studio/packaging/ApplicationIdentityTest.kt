package com.autoscript.studio

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationIdentityTest {
    private val root = File(requireNotNull(System.getProperty("autoscript.projectRoot")))
    private fun source(path: String) = File(root, path).readText()

    @Test fun installationIdentitiesAgreeWithoutRenamingComponentClasses() {
        val studio = source("android/apps/studio-android/build.gradle.kts")
        val runner = source("android/apps/runner-template-android/build.gradle.kts")
        assertTrue(studio.contains("applicationId = \"com.zhigou.kaifaban\""))
        assertTrue(studio.contains("namespace = \"com.autoscript.studio\""))
        assertTrue(runner.contains("releaseString(\"applicationId\", \"com.zhigou.yunxingban\")"))
        assertTrue(runner.contains("namespace = \"com.autoscript.runner\""))
        assertTrue(studio.contains("\"applicationId\":\"com.zhigou.yunxingban\""))
        assertTrue(source("android/apps/studio-android/src/main/AndroidManifest.xml")
            .contains("<package android:name=\"com.zhigou.yunxingban\""))
        assertTrue(source("rust/tools/studio-compiler-jni/src/release_prepare.rs")
            .contains("application_id: \"com.zhigou.yunxingban\""))
        assertTrue(DevicePackagingController.APPLICATION_ID == "com.zhigou.yunxingban")
    }
}
