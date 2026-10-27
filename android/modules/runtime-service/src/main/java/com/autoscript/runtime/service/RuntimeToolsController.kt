package com.autoscript.runtime.service

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.graphics.Point
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import com.autoscript.runtime.api.RecordingReply

/** One bounded I/O worker; MediaPlayer and window ownership stay on the main thread. */
internal class RuntimeToolsController(
    private val context: Context,
    private val recorder: RuntimeInputRecordingController,
    private val generation: () -> Long,
    private val ready: () -> Boolean,
    private val screen: () -> Pair<Point, Int>,
    private val openAudio: (String) -> FileInputStream,
    private val floatingImages:RuntimeFloatingImages,
    private val inputFeatures:()->Int={0},
) {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val platform=RuntimePlatformTools(context)
    private val floating=RuntimeFloatingWindows(context) {screen().first}
    private val interactive=RuntimeInteractiveImages(context) {screen().first}
    private val http=RuntimeHttpRequests()
    private val keepAwake=RuntimeScreenAwakeLease(context)
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1))
    private val epoch = AtomicLong()
    private var player: MediaPlayer? = null
    private var playerWork = 0L
    private var releaseTimer: Runnable? = null

    fun submit(work: Long, operation: String, value: String, cancelled: () -> Boolean, callback: (Result<String>) -> Unit) {
        val completion = RuntimeToolCompletion(callback)
        fun complete(result: Result<String>) = completion.finish(result)
        val ticket = epoch.get()
        val run = generation()
        fun valid() = epoch.get() == ticket && generation() == run && ready() && !cancelled()
        val imeTicket=if(operation=="imeText") RuntimeImeConnection.ticket() else null
        if(operation=="httpRequest") {http.submit(work,value,::valid,::complete);return}
        if(operation=="imageEvent") {
            main.post {runCatching {
                val spec=ProjectDataCommands.parse(value,1024).asJsonObject
                require(spec.keySet()==setOf("name","timeoutMs"))
                interactive.await(spec["name"].asString,requireNotNull(spec["timeoutMs"].toString().toLongOrNull()),work,::valid,::complete)
            }.onFailure {complete(Result.failure(it))}}
            return
        }
        if(operation=="imageClose") {main.post {complete(runCatching {check(valid());interactive.close(value);""})};return}
        runCatching {
            worker.execute {
                try {
                    check(valid())
                    when (operation) {
                        "imageShow" -> {
                            val spec=ProjectDataCommands.parse(value,2048).asJsonObject
                            val image=floatingImages.bitmap(spec["imagePath"].asString)
                            check(valid());main.post {complete(runCatching {check(valid());interactive.show(value,image,work);""})}
                        }
                        "imeText", "imeStatus" -> main.post {
                            complete(runCatching {
                                check(valid())
                                if(operation=="imeText") {check(RuntimeImeConnection.connected()) {"IME_NOT_CONNECTED"};check(RuntimeImeConnection.commit(imeTicket,value)) {"IME_COMMIT_REJECTED"};""}
                                else {
                                    val component=android.content.ComponentName(context,RuntimeInputMethodService::class.java)
                                    val manager=context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                                    com.google.gson.JsonObject().apply {
                                        addProperty("enabled",manager.enabledInputMethodList.any {android.content.ComponentName(it.packageName,it.serviceName)==component})
                                        addProperty("selected",android.content.ComponentName.unflattenFromString(android.provider.Settings.Secure.getString(context.contentResolver,android.provider.Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty())==component)
                                        addProperty("connected",RuntimeImeConnection.connected())
                                    }.toString()
                                }
                            })
                        }
                        "deviceInfo" -> {
                            val result=RuntimeDeviceInfo.read(context,value,ready(),inputFeatures())
                            check(valid());complete(Result.success(result))
                        }
                        "openUrl", "keepScreenAwake" -> main.post {
                            complete(runCatching {
                                check(valid())
                                if(operation=="openUrl") {
                                    val uri=RuntimeHttpSpec.url(value)
                                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(uri.toASCIIString()))
                                        .addCategory(android.content.Intent.CATEGORY_BROWSABLE).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                                } else {require(value in setOf("0","1"));keepAwake.set(value=="1",work)}
                                ""
                            })
                        }
                        "floatingShow" -> {
                            val spec=FloatingCanvasSpec.parse(value)
                            val image=if(spec.imagePath.isEmpty()) null else floatingImages.bitmap(spec.imagePath)
                            check(valid())
                            main.post {complete(runCatching {check(valid()); floating.show(spec,image,work); ""})}
                        }
                        "floatingBounds" -> main.post {
                            runCatching {check(valid()); floating.awaitBounds(value,::valid,::complete)}.onFailure {complete(Result.failure(it))}
                        }
                        "floatingHide", "floatingClose" -> main.post {
                            complete(runCatching {
                                check(valid())
                                if(operation=="floatingHide") floating.hide(value) else floating.close(value)
                                ""
                            })
                        }
                        "appInfo" -> {
                            val result=platform.execute(operation,value)
                            check(valid()); complete(Result.success(result))
                        }
                        "clipboardGet", "clipboardSet", "appOpen" -> main.post {
                            complete(runCatching { check(valid()); platform.execute(operation,value) })
                        }
                        "recordValidate" -> {
                            val recorded = JSONObject(value)
                            val (size, rotation) = screen()
                            require(recorded.getInt("width") == size.x && recorded.getInt("height") == size.y &&
                                recorded.getInt("rotation") == rotation)
                            check(valid())
                            complete(Result.success(value))
                        }
                        "record" -> {
                            val config = JSONObject(value)
                            require(config.length() == 2)
                            val duration = config.getInt("durationMs")
                            val budget = config.getInt("eventBudget")
                            require(duration in 1000..60000 && budget in 128..512)
                            val (size, rotation) = screen()
                            val result = AtomicReference<RecordingReply?>()
                            val done = CountDownLatch(1)
                            val started = recorder.start(work, run, size.x, size.y, rotation, duration, budget) {
                                result.set(it); done.countDown()
                            }
                            check(started.status == RecordingReply.SUCCESS)
                            if (!valid()) { recorder.cancel(work, run); error("cancelled") }
                            check(done.await(duration.toLong() + 8000, TimeUnit.MILLISECONDS))
                            check(valid())
                            val reply = requireNotNull(result.get())
                            require(reply.valid() && reply.status == RecordingReply.SUCCESS)
                            complete(Result.success(encodeRecording(reply)))
                        }
                        "audioPlay" -> {
                            val input = openAudio(value)
                            if (!valid()) { input.close(); error("cancelled") }
                            main.post {
                                try {
                                    input.use {
                                        check(valid())
                                        releasePlayer()
                                        val next = MediaPlayer()
                                        player = next; playerWork = work
                                        val timeout = Runnable {
                                            if (player === next) { releasePlayer(); complete(Result.failure(IllegalStateException("audio prepare timeout"))) }
                                        }
                                        releaseTimer = timeout
                                        next.setOnPreparedListener {
                                            main.removeCallbacks(timeout)
                                            if (player !== next || !valid()) { if (player === next) releasePlayer(); return@setOnPreparedListener }
                                            try {
                                                next.start()
                                                val expiry = Runnable { if (player === next) releasePlayer() }
                                                releaseTimer = expiry
                                                main.postDelayed(expiry, 60000)
                                                complete(Result.success(""))
                                            } catch (error: Exception) {
                                                if (player === next) releasePlayer()
                                                complete(Result.failure(error))
                                            }
                                        }
                                        next.setOnCompletionListener { if (player === next) releasePlayer() }
                                        next.setOnErrorListener { _, _, _ ->
                                            if (player === next) { releasePlayer(); complete(Result.failure(IllegalStateException("audio decode failed"))) }
                                            true
                                        }
                                        next.setDataSource(it.fd)
                                        next.prepareAsync()
                                        main.postDelayed(timeout, 1500)
                                    }
                                } catch (error: Exception) { releasePlayer(); complete(Result.failure(error)) }
                            }
                        }
                        "audioStop", "audioGetVolume", "audioSetVolume" -> main.post {
                            complete(runCatching {
                                check(valid())
                                when (operation) {
                                    "audioStop" -> { releasePlayer(); "" }
                                    "audioGetVolume" -> {
                                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                        ((audio.getStreamVolume(AudioManager.STREAM_MUSIC).toLong() * 100 + max / 2) / max.coerceAtLeast(1)).toString()
                                    }
                                    else -> {
                                        val volume = value.toInt(); require(volume in 0..100)
                                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max.toLong() * volume / 100).toInt(), 0)
                                        ""
                                    }
                                }
                            })
                        }
                        else -> error("unknown runtime tool")
                    }
                } catch (error: Exception) {
                    if (operation == "record") recorder.cancel(work, run)
                    complete(Result.failure(error))
                }
            }
        }.onFailure { complete(Result.failure(it)) }
    }

    fun cancel(work: Long) {
        http.cancel(work)
        recorder.cancel(work, generation())
        main.post { if (playerWork == work) releasePlayer(); floating.cancel(work);interactive.cancel(work);keepAwake.cancel(work) }
    }
    fun reset() { epoch.incrementAndGet(); recorder.abort();http.reset(); main.post {releasePlayer(); floating.clear();interactive.clear(); floatingImages.releaseDecoded();keepAwake.clear()} }
    fun close() { reset();http.close(); worker.shutdownNow(); recorder.close() }
    private fun releasePlayer() {
        releaseTimer?.let(main::removeCallbacks); releaseTimer = null
        player?.let { runCatching { it.release() } }; player = null; playerWork = 0
    }
    companion object {
        internal fun encodeRecording(reply: RecordingReply): String {
            require(reply.valid() && reply.status == RecordingReply.SUCCESS)
            val data = ByteBuffer.wrap(reply.actions).order(ByteOrder.LITTLE_ENDIAN)
            val actions = com.google.gson.JsonArray()
            while (data.hasRemaining()) {
                val action = com.google.gson.JsonArray()
                repeat(8) { action.add(data.int) }
                actions.add(action)
            }
            val json = com.google.gson.JsonObject().apply {
                addProperty("width", reply.width); addProperty("height", reply.height)
                addProperty("rotation", reply.rotation); addProperty("elapsedMs", reply.elapsedMs)
                addProperty("skippedGestures", reply.skippedGestures); add("actions", actions)
            }.toString()
            require(json.toByteArray(Charsets.UTF_8).size <= 32768)
            return json
        }
    }
}

/** Timeout, decoder and cancellation races may report only one host result. */
internal class RuntimeToolCompletion(private val callback: (Result<String>) -> Unit) {
    private val finished = java.util.concurrent.atomic.AtomicBoolean(false)
    fun finish(result: Result<String>) {
        if (finished.compareAndSet(false, true)) callback(result)
    }
}
