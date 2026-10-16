package com.autoscript.studio

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class RunnerApkSizeReportTest {
    @Test fun optionalLibrariesAreSeparatedWithoutDoubleCountingNestedRunner() {
        assertEquals("OpenCV · arm64-v8a", RunnerApkSizeReport.category("lib/arm64-v8a/libopencv_java4.so"))
        assertEquals("ONNX Runtime · x86_64", RunnerApkSizeReport.category("lib/x86_64/libonnxruntime.so"))
        assertEquals("ONNX Runtime · x86_64", RunnerApkSizeReport.category("lib/x86_64/libonnxruntime4j_jni.so"))
        assertEquals("原生库 · arm64-v8a", RunnerApkSizeReport.category("lib/arm64-v8a/libc++_shared.so"))
        assertEquals("内嵌Runner模板", RunnerApkSizeReport.category("assets/packaging/runner-template.apk"))
    }
    @Test fun componentsReportBothAbisAndDoNotExposeProjectResourceNames() {
        val apk = Files.createTempFile("runner-report", ".apk").toFile()
        try {
            ZipOutputStream(apk.outputStream()).use { output ->
                listOf("lib/arm64-v8a/libengine_jni.so", "lib/x86_64/libengine_jni.so", "assets/letters.onnx",
                    "assets/autoscript-release/private-image.png", "classes2.dex", "AndroidManifest.xml").forEach { name ->
                    output.putNextEntry(ZipEntry(name)); output.write(ByteArray(4)); output.closeEntry()
                }
            }
            val report = RunnerApkSizeReport.read(apk) {}
            assertEquals(apk.length(), report.apkBytes)
            assertEquals(4L, report.components["ONNX模型"])
            assertEquals(4L, report.components["项目冻结脚本与资源"])
            assertEquals(6, report.components.size)
            assertEquals(report.apkBytes, report.compressedPayloadBytes + report.layoutBytes)
            assertTrue(report.layoutBytes > 0)
            assertEquals(report.components.keys, report.compressedComponents.keys)
            assertEquals("内嵌Runner模板", RunnerApkSizeReport.category("assets/packaging/runner-template.apk"))
            assertFalse(report.text().contains("private-image"))
            assertTrue(report.text().contains("不含签名/对齐"))
            assertThrows(CancellationException::class.java) { RunnerApkSizeReport.read(apk) { throw CancellationException() } }
        } finally { apk.delete() }
    }
}
