package com.autoscript.studio

import org.junit.Assert.*
import org.junit.Test

class RunnerComponentDescriptorTest {
    private val protocol = com.autoscript.runtime.api.RuntimeProtocol.VERSION
    private val componentVersion = RunnerComponentDescriptor.COMPONENT_VERSION
    private val base = """{"formatVersion":1,"kind":"runner-template","componentVersion":$componentVersion,"runtimeProtocol":$protocol,"runtimeApi":"1.8","family":"base","applicationId":"com.zhigou.yunxingban","versionCode":1,"versionName":"0.1.0","minSdk":24,"abis":["arm64-v8a"],"dependencies":{}}"""
    @Test fun exactBaseDescriptorIsAccepted() {
        assertEquals(RunnerComponentDescriptor(RunnerTemplateFamily.BASE, setOf("arm64-v8a")), RunnerComponentDescriptor.parse(base.toByteArray()))
    }
    @Test fun protocolAbiVersionAndUnknownOrDuplicateKeysAreRejected() {
        listOf(base.replace("\"runtimeProtocol\":$protocol", "\"runtimeProtocol\":${protocol - 1}"), base.replace("1.8", "1.7"), base.replace("arm64-v8a", "x86_64"),
            base.replace("\"componentVersion\":$componentVersion", "\"componentVersion\":${componentVersion - 1}"),
            base.replace("\"base\"", "\"full\""), base.replace(":24", ":23"), base.replace("\"componentVersion\":$componentVersion", "\"componentVersion\":$componentVersion,\"unknown\":1"),
            base.replace("\"family\":\"base\"", "\"family\":\"base\",\"family\":\"full\""), base + "{}",
            base.replace("\"abis\":[\"arm64-v8a\"]", "\"abis\":[\"arm64-v8a\",\"arm64-v8a\"]"),
            base.replace("\"formatVersion\":1", "\"formatVersion\":1.0"), base.replace("\"formatVersion\"", "formatVersion"))
            .forEach { invalid -> assertThrows(Exception::class.java) { RunnerComponentDescriptor.parse(invalid.toByteArray()) } }
    }
    @Test fun descriptionSizeAndNestingAreBounded() {
        assertThrows(Exception::class.java) { RunnerComponentDescriptor.parse(ByteArray(8193)) }
        assertThrows(Exception::class.java) { RunnerComponentDescriptor.parse("""{"a":{"a":{"a":{"a":{"a":1}}}}}""".toByteArray()) }
    }
}
