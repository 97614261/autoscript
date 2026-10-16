package com.autoscript.studio

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import java.util.UUID

/** One bounded, process-local ticket. No source, project path or result is logged/persisted. */
internal object EditorSystemRequests {
    data class Pending(val ticket: String, val intent: Intent, val result: (Int, Intent?) -> Unit,
        var cancelUi: (() -> Unit)? = null)
    private val tickets = EditorRequestTickets<Pending>()
    private val handler = Handler(Looper.getMainLooper())
    private var timeout: Runnable? = null
    fun begin(context: Context, intent: Intent, owner: EditorOverlaySession, result: (Int, Intent?) -> Unit) {
        check(!owner.isClosed)
        val ticket = UUID.randomUUID().toString()
        tickets.begin(owner, ticket, Pending(ticket, intent, result))
        timeout = Runnable { finish(ticket, Activity.RESULT_CANCELED, null) }.also { handler.postDelayed(it, 120_000) }
        try {
            context.startActivity(Intent(context, EditorSystemRequestActivity::class.java)
                .putExtra("ticket", ticket).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (failure: Exception) {
            finish(ticket, Activity.RESULT_CANCELED, null)
            throw failure
        }
    }
    fun find(ticket: String?): Pending? = tickets.find(ticket)
    fun finish(ticket: String, code: Int, data: Intent?) {
        val request = tickets.take(ticket) ?: return
        timeout?.let(handler::removeCallbacks); timeout = null
        try { request.result(code, data) }
        finally { request.cancelUi?.invoke() }
    }
    fun cancel(owner: EditorOverlaySession) { tickets.ticketFor(owner)?.let { finish(it, Activity.RESULT_CANCELED, null) } }
}

internal class EditorActivityResults(private val context: Context, private val host: EditorOverlaySession,
    private val onReturned: () -> Unit) : ActivityResultRegistryOwner {
    override val activityResultRegistry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
            options: ActivityOptionsCompat?) {
            if (host.isClosed) return
            contract.getSynchronousResult(context, input)?.let { dispatchResult(requestCode, it.value); return }
            host.systemRequest(true)
            var completed = false
            fun complete(code: Int, data: Intent?) {
                if (completed) return
                completed = true
                if (host.isClosed) return
                host.systemRequest(false)
                dispatchResult(requestCode, code, data)
                onReturned()
            }
            try {
                EditorSystemRequests.begin(context, contract.createIntent(context, input), host) { code, data ->
                    complete(code, data)
                }
            } catch (_: Exception) {
                complete(Activity.RESULT_CANCELED, null)
            }
        }
    }
}

/** Exported=false; only a current service-owned ticket may launch a system picker/permission request. */
class EditorSystemRequestActivity : ComponentActivity() {
    private var ticket: String? = null
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        ticket?.let { EditorSystemRequests.finish(it, result.resultCode, result.data) }
        finish()
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val data = Intent().putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS, results.keys.toTypedArray())
            .putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS,
                results.values.map { if (it) android.content.pm.PackageManager.PERMISSION_GRANTED else android.content.pm.PackageManager.PERMISSION_DENIED }.toIntArray())
        ticket?.let { EditorSystemRequests.finish(it, Activity.RESULT_OK, data) }
        finish()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ticket = intent.getStringExtra("ticket")
        val request = EditorSystemRequests.find(ticket) ?: run { finish(); return }
        request.cancelUi = { finish() }
        if (savedInstanceState == null) {
            runCatching {
                if (request.intent.action == ActivityResultContracts.RequestMultiplePermissions.ACTION_REQUEST_PERMISSIONS)
                    permissions.launch(request.intent.getStringArrayExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS).orEmpty().toList().toTypedArray())
                else picker.launch(request.intent)
            }.onFailure {
                android.widget.Toast.makeText(this, "系统未提供此选择入口", android.widget.Toast.LENGTH_LONG).show()
                ticket?.let { EditorSystemRequests.finish(it, Activity.RESULT_CANCELED, null) }
                finish()
            }
        }
    }
    override fun onDestroy() {
        if (isFinishing) ticket?.let { EditorSystemRequests.finish(it, Activity.RESULT_CANCELED, null) }
        super.onDestroy()
    }
}
