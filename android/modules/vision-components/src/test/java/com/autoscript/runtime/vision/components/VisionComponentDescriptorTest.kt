package com.autoscript.runtime.vision.components

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CancellationException
import java.util.zip.ZipFile

class VisionComponentDescriptorTest {
    private fun descriptor(kind: String): ByteArray = ZipFile(File(requireNotNull(System.getProperty("vision.components.$kind")))).use {
        it.getInputStream(requireNotNull(it.getEntry(VisionComponentDescriptor.PATH))).use { input -> input.readBytes() }
    }

    @Test fun exportedDescriptorsHaveFixedFactoriesAndBoundedVersions() {
        for (kind in VisionComponentKind.entries) {
            val parsed = VisionComponentDescriptor.parse(descriptor(kind.id))
            assertEquals(kind, parsed.kind)
            assertEquals(setOf("arm64-v8a", "x86_64").intersect(parsed.abis), parsed.abis)
        }
    }

    private fun rejected(key: String, text: String, compatibility: Boolean = false) {
        val json = JsonParser.parseString(descriptor("opencv").toString(Charsets.UTF_8)).asJsonObject
        json.addProperty(key, text)
        assertThrows(if (compatibility) VisionComponentCompatibilityException::class.java else IllegalArgumentException::class.java) {
            VisionComponentDescriptor.parse(json.toString().toByteArray())
        }
    }

    @Test fun scriptChosenClassAndWrongSdkAreRejected() {
        rejected("providerClass", "custom.ArbitraryClass", true)
        rejected("sdkVersion", "5.0.0", true)
        rejected("applicationId", "com.example.untrusted", true)
    }

    @Test fun unknownFieldsDuplicateKeysAndTrailingDataAreRejected() {
        rejected("nativePath", "/arbitrary/native.so")
        val source = descriptor("opencv").toString(Charsets.UTF_8)
        assertThrows(Exception::class.java) { VisionComponentDescriptor.parse((source + "{}").toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) {
            VisionComponentDescriptor.parse(source.replaceFirst("{", "{\"kind\":\"opencv\",").toByteArray())
        }
    }

    @Test fun wrongNumericTypesAndSpiAreRejected() {
        rejected("spiVersion", "1")
        val root = JsonParser.parseString(descriptor("opencv").toString(Charsets.UTF_8)).asJsonObject
        root.addProperty("spiVersion", 99)
        val source = root.toString()
        assertThrows(VisionComponentCompatibilityException::class.java) { VisionComponentDescriptor.parse(source.toByteArray()) }
    }

    @Test fun modelPathSizeAndHashArePinned() {
        for ((field, value) in listOf("path" to "assets/custom.onnx", "size" to "1", "sha256" to "0".repeat(64))) {
            val root = JsonParser.parseString(descriptor("onnx").toString(Charsets.UTF_8)).asJsonObject
            root.getAsJsonObject("model").addProperty(field, value)
            assertThrows(IllegalArgumentException::class.java) { VisionComponentDescriptor.parse(root.toString().toByteArray()) }
        }
    }

    @Test fun duplicateAbisAndWrongLicensesAreRejected() {
        val root = JsonParser.parseString(descriptor("opencv").toString(Charsets.UTF_8)).asJsonObject
        root.getAsJsonArray("abis").add(root.getAsJsonArray("abis").first().asString)
        assertThrows(IllegalArgumentException::class.java) { VisionComponentDescriptor.parse(root.toString().toByteArray()) }
        root.remove("abis"); root.add("abis", JsonParser.parseString("[\"arm64-v8a\"]"))
        root.add("licenses", JsonParser.parseString("[\"assets/custom/LICENSE.txt\"]"))
        assertThrows(IllegalArgumentException::class.java) { VisionComponentDescriptor.parse(root.toString().toByteArray()) }
    }

    @Test fun supportedSingleAndDualAbisAreAcceptedButEmptyAndUnknownAreRejected() {
        val root = JsonParser.parseString(descriptor("opencv").toString(Charsets.UTF_8)).asJsonObject
        for (abis in listOf("[\"arm64-v8a\"]", "[\"x86_64\"]", "[\"arm64-v8a\",\"x86_64\"]")) {
            root.add("abis", JsonParser.parseString(abis))
            assertEquals(JsonParser.parseString(abis).asJsonArray.map { it.asString }.toSet(),
                VisionComponentDescriptor.parse(root.toString().toByteArray()).abis)
        }
        for (abis in listOf("[]", "[\"armeabi-v7a\"]", "[\"x86_64\",\"x86_64\"]")) {
            root.add("abis", JsonParser.parseString(abis))
            assertThrows(IllegalArgumentException::class.java) { VisionComponentDescriptor.parse(root.toString().toByteArray()) }
        }
    }

    @Test fun malformedUtf8AndResourceBudgetFailClosed() {
        assertThrows(Exception::class.java) { VisionComponentDescriptor.parse(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertThrows(IllegalArgumentException::class.java) { VisionComponentDescriptor.parse(ByteArray(8193)) }
        assertThrows(IllegalArgumentException::class.java) {
            VisionComponentValidator.readBounded(ByteArrayInputStream(ByteArray(1025)), 1024) {}
        }
        assertThrows(CancellationException::class.java) {
            VisionComponentValidator.hash(ByteArrayInputStream(ByteArray(1)), 1) { throw CancellationException() }
        }
    }

    @Test fun manifestMustHaveNoExecutableAndroidComponentsOrPermissions() {
        val descriptor = VisionComponentDescriptor.parse(descriptor("opencv"))
        val manifest = VisionComponentManifest(descriptor.kind.applicationId, descriptor.componentVersion, descriptor.kind.sdkVersion, 24, true, 0, 0)
        manifest.check(descriptor)
        for ((bad, code) in listOf(manifest.copy(componentCount = 1) to "VISION_COMPONENT_UNEXPECTED_ENTRYPOINT",
            manifest.copy(permissionCount = 1) to "VISION_COMPONENT_UNEXPECTED_PERMISSION",
            manifest.copy(applicationId = "com.example.other") to "VISION_COMPONENT_IDENTITY_MISMATCH",
            manifest.copy(minSdk = 25) to "VISION_COMPONENT_MIN_SDK_MISMATCH",
            manifest.copy(hasCode = false) to "VISION_COMPONENT_CODE_MISSING")) {
            assertEquals(code, assertThrows(IllegalArgumentException::class.java) { bad.check(descriptor) }.message)
        }
    }

    @Test fun v1RemainsOriginalOnlyAndV2PinsBothModels() {
        val root = JsonParser.parseString(descriptor("onnx").toString(Charsets.UTF_8)).asJsonObject
        root.addProperty("componentVersion", 2); root.addProperty("versionCode", 2); root.addProperty("spiVersion", 2)
        val v2 = VisionComponentDescriptor.parse(root.toString().toByteArray())
        assertEquals(2, v2.componentVersion)
        assertEquals(listOf("model.onnx", "model-v5.onnx"), v2.models.map { it.snapshotName })
        val candidate = root.getAsJsonObject("candidateModel").deepCopy()
        for (key in listOf("path", "size", "sha256")) {
            root.add("candidateModel", candidate.deepCopy().apply { addProperty(key, "invalid") })
            assertThrows(IllegalArgumentException::class.java) { VisionComponentDescriptor.parse(root.toString().toByteArray()) }
        }
        root.remove("candidateModel")
        root.addProperty("componentVersion", 1); root.addProperty("versionCode", 1); root.addProperty("spiVersion", 1)
        assertEquals(1, VisionComponentDescriptor.parse(root.toString().toByteArray()).models.size)
        root.add("candidateModel", candidate)
        assertThrows(IllegalArgumentException::class.java) { VisionComponentDescriptor.parse(root.toString().toByteArray()) }
    }

    @Test fun v3UsesTheSameTwoFixedModelsAsV2() {
        val root = JsonParser.parseString(descriptor("onnx").toString(Charsets.UTF_8)).asJsonObject
        root.addProperty("componentVersion", 3); root.addProperty("versionCode", 3); root.addProperty("spiVersion", 3)
        val third = VisionComponentDescriptor.parse(root.toString().toByteArray())
        root.addProperty("componentVersion", 2); root.addProperty("versionCode", 2); root.addProperty("spiVersion", 2)
        assertEquals(VisionComponentDescriptor.parse(root.toString().toByteArray()).models, third.models)
        assertEquals(3, third.spiVersion)
    }
}
