package com.autoscript.runtime.vision.components

import java.io.RandomAccessFile

/** Public test helper only, not an Android component or production class. */
object VisionRegistryLeaseProcess {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1)
        RandomAccessFile(args.single(), "rw").use { file ->
            file.channel.lock().use {
                println("READY")
                System.`in`.read()
            }
        }
    }
}
