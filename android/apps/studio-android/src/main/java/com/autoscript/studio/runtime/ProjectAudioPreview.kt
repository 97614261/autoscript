package com.autoscript.studio

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Explicit, short preview; never starts from opening a resource or changing tabs. */
@Composable
internal fun ProjectAudioPreview(file: File) {
    val scope = rememberCoroutineScope()
    val main = remember { Handler(Looper.getMainLooper()) }
    var player by remember(file.path) { mutableStateOf<MediaPlayer?>(null) }
    var active by remember(file.path) { mutableStateOf(false) }
    var error by remember(file.path) { mutableStateOf<String?>(null) }
    val alive = remember(file.path) { java.util.concurrent.atomic.AtomicBoolean(true) }
    val ticket = remember(file.path) { java.util.concurrent.atomic.AtomicLong() }
    var deadline by remember(file.path) { mutableStateOf<Runnable?>(null) }
    fun release() {
        ticket.incrementAndGet()
        deadline?.let(main::removeCallbacks); deadline = null
        player?.let { runCatching { it.release() } }; player = null; active = false
    }
    DisposableEffect(file.path) { onDispose { alive.set(false); release() } }
    TextButton(onClick = {
        if (active) release() else {
            active = true; error = null
            val expected = ticket.get()
            scope.launch {
                try {
                    val validated = withContext(Dispatchers.IO) {
                        require(file.canonicalFile == file.absoluteFile && file.isFile && file.length() in 1..20L * 1024 * 1024)
                        file
                    }
                    check(alive.get() && ticket.get() == expected)
                    validated.inputStream().use {
                        val next = MediaPlayer(); player = next
                        val timeout = Runnable { if (player === next) { error = "音频准备超时"; release() } }
                        deadline = timeout
                        next.setOnPreparedListener {
                            main.removeCallbacks(timeout)
                            if (player === next && alive.get()) {
                                runCatching { next.start() }.onFailure { error = "无法播放该音频"; release() }
                                if (player === next) {
                                    val expiry = Runnable { if (player === next) release() }
                                    deadline = expiry; main.postDelayed(expiry,20_000)
                                }
                            }
                        }
                        next.setOnCompletionListener { if (player === next) release() }
                        next.setOnErrorListener { _, _, _ -> if (player === next) { error = "设备无法解码该音频"; release() }; true }
                        next.setDataSource(it.fd); next.prepareAsync(); main.postDelayed(timeout,1500)
                    }
                } catch (_: Exception) { if (alive.get() && ticket.get() == expected) { error = "音频试听失败"; release() } }
            }
        }
    }) { Text(if (active) "停止试听" else "试听（最多20秒）", fontSize = 11.sp) }
    error?.let { Text(it, fontSize = 11.sp) }
}
