package com.autoscript.studio

import android.app.Instrumentation
import android.content.ClipboardManager
import android.media.AudioManager
import androidx.activity.ComponentActivity
import com.autoscript.project.store.*
import com.autoscript.runtime.client.RuntimeClient
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject

/** Device state changed by public QA calls is restored, including the original ClipData. */
internal object LibraryDeviceUsageFixture {
    fun run(test: Instrumentation, activity: ComponentActivity, client: RuntimeClient, store: ProjectStore,
        initial: ProjectSnapshot, record: (String, () -> Unit) -> Unit): ProjectSnapshot {
        val rt = OwnedUsageRuntime(client, store, initial)
        rt.variables("text" to ProjectVariableType.STRING, "value" to ProjectVariableType.INTEGER,
            "width" to ProjectVariableType.INTEGER, "height" to ProjectVariableType.INTEGER, "clock" to ProjectVariableType.INTEGER)
        val audio = activity.getSystemService(AudioManager::class.java)
        val originalVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        var originalClip: android.content.ClipData? = null
        test.runOnMainSync { originalClip = clipboard.primaryClip }
        try {
            record("library-system.time-elapsed-and-screensize-real-device") {
                val before = System.currentTimeMillis()
                rt.run(listOf(rt.block("system.time", "resultVariable" to "value"),
                    rt.block("system.elapsed", "resultVariable" to "clock"),
                    rt.block("system.screensize", "widthVariable" to "width", "heightVariable" to "height"))) {
                    check(it["value"]?.toLongOrNull() in before..System.currentTimeMillis())
                    check((it["clock"]?.toLongOrNull() ?: 0) > 0)
                    check((it["width"]?.toIntOrNull() ?: 0) > 0 && (it["height"]?.toIntOrNull() ?: 0) > 0)
                }
            }
            record("library-clipboard-set-and-get-public-unicode-with-original-restored") {
                rt.run(listOf(rt.block("clipboard.settext", "text" to "PUBLIC_QA_智构123"),
                    rt.block("clipboard.gettext", "resultVariable" to "text"))) { check(it["text"] == "PUBLIC_QA_智构123") }
            }
            record("library-app.info-queries-own-visible-package") {
                rt.run(listOf(rt.block("app.info", "packageName" to test.targetContext.packageName, "resultVariable" to "text"))) {
                    check(JSONObject(it["text"].orEmpty()).getBoolean("visible"))
                }
            }
            record("library-audio-volume-play-and-stop-owned-silent-wave") {
                val cache = java.io.File(activity.cacheDir, "runtime-audio")
                test.sendStatus(1, android.os.Bundle().apply { putString("stream", "QA_AUDIO_CACHE:${cache.absolutePath}:${cache.canonicalPath}:${cache.isDirectory}:${cache.listFiles()?.size}\n") })
                val pcm = ByteArray(1600)
                val wav = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
                    put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVEfmt ".toByteArray())
                    putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
                    put("data".toByteArray()); putInt(pcm.size); put(pcm)
                }.array()
                val snapshot = store.openProject(rt.id)
                val imported = store.importResource(rt.id, ProjectResourceKind.AUDIO, "public-silence.wav", wav.inputStream(),
                    snapshot.manifest.resources.map { it["path"].asString }.toSet())
                val path = imported.manifest.resources.single { it["kind"].asString == "audio" }["path"].asString
                rt.run(listOf(rt.block("audio.getvolume", "resultVariable" to "value"),
                    rt.block("audio.setvolume", "volume" to 0), rt.block("audio.play", "path" to path),
                    rt.block("audio.stop"))) { check(it["value"]?.toIntOrNull() in 0..100) }
            }
            return store.openProject(rt.id)
        } finally {
            rt.stop()
            test.runOnMainSync {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
                originalClip?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip()
            }
            check(audio.getStreamVolume(AudioManager.STREAM_MUSIC) == originalVolume)
        }
    }
}
