package com.autoscript.runtime.service

import org.junit.Assert.*
import org.junit.Test

class RuntimePlatformToolsTest {
    @Test fun packageNamesCannotBeUrlsPathsOrCommandArguments() {
        assertTrue(RuntimePlatformTools.validPackage("com.android.settings"))
        for (name in listOf("", "../app", "https://app", "com.foo;reboot", "com..foo", "com.1foo", " com.foo", "com.你好"))
            assertFalse(name,RuntimePlatformTools.validPackage(name))
    }
}
