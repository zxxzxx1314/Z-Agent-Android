// Copyright (c) 2026 Agoni
// This file is part of Z-Agent and is released under the MIT License.
// See the LICENSE file in the project root for full license information.
package cn.rain.zagent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean

/**

Share entry: invoked from any app via ACTION_PROCESS_TEXT or ACTION_SEND.

Transparent theme, excludeFromRecents, empty taskAffinity so it does

not steal the host app back stack.

ACTION_PROCESS_TEXT returns a value: the caller waits for finish()

then reads EXTRA_PROCESS_TEXT. On timeout we must still return

RESULT_OK with the original text; RESULT_CANCELED payloads are

ignored by the caller.

ACTION_SEND has no return channel; result goes to a notification.

runAgent blocks on the Chaquopy interpreter, so it always runs on a

worker thread.

Shared text is untrusted. Any parser tag (script:, evolve:, act:) is

rejected before reaching the model, so third-party content cannot

drive the code execution path.

Known limitation: runAgent shares the global session and _STREAM_STOP

with MainActivity. Isolating them needs a Python-side ephemeral entry.
*/
class ShareActivity : Activity() {

    private val done = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private var timer: Runnable? = null
    private val LB = '['
    private val RB = ']'

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
 Dimens.init(resources)
        try { AndroidAPI.init(applicationContext) } catch (e: Throwable) {}
        requestNotifyPermissionIfNeeded()
        val it0 = intent
        if (it0 == null) {
            finish()
            return
        }
        when (it0.action) {
            Intent.ACTION_PROCESS_TEXT -> handleProcessText(it0)
            Intent.ACTION_SEND -> handleSend(it0)
            else -> finish()
        }
    }

    private fun requestNotifyPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        try {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        } catch (e: Throwable) {
        }
    }

    private fun handleProcessText(it0: Intent) {
        val text = it0.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        val readonly = it0.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        if (text.isBlank()) {
            finish()
            return
        }
        if (readonly) {
            // Caller does not accept a return value; running would only burn quota.
            finish()
            return
        }
        if (hasParserTag(text)) {
            toast(getString(R.string.share_blocked))
            finish()
            return
        }
        runInWorker(text, true)
    }

    private fun handleSend(it0: Intent) {
        var text = it0.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        if (text.isBlank()) {
            val arr = it0.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)
            if (arr != null) {
                text = arr.joinToString(System.lineSeparator()) { it?.toString().orEmpty() }
            }
        }
        if (text.isBlank()) {
            toast(getString(R.string.share_empty))
            finish()
            return
        }
        if (hasParserTag(text)) {
            toast(getString(R.string.share_blocked))
            finish()
            return
        }
        runInWorker(text, false)
    }

    /** Shared text is untrusted; reject anything the parser would treat as a tag. */
    private fun hasParserTag(s: String): Boolean {
        val low = s.lowercase()
        if (low.contains(LB + "script:" + RB)) return true
        if (low.contains(LB + "script" + RB)) return true
        if (low.contains(LB + "/script" + RB)) return true
        if (low.contains(LB + "evolve:")) return true
        if (low.contains(LB + "act:")) return true
        return false
    }

    /** canReturn = true means the caller waits for a result. */
    private fun runInWorker(raw: String, canReturn: Boolean) {
        val prompt = buildPrompt(raw)
        timer = Runnable {
            if (done.compareAndSet(false, true)) {
                if (canReturn) {
                    setResult(RESULT_OK, replyWith(raw))
                } else {
                    notifyResult(false, getString(R.string.share_timeout))
                }
                finish()
            }
        }
        handler.postDelayed(timer!!, TIMEOUT_MS)
        Thread {
            var out: String? = null
            try {
                out = PythonBridge.runAgent(applicationContext, prompt)
            } catch (e: Throwable) {
                Log.e("ShareActivity: " + (e.message ?: ""), e)
            }
            if (!done.compareAndSet(false, true)) return@Thread
            val captured = out
            handler.post {
                timer?.let { handler.removeCallbacks(it) }
                val text = (captured ?: "").trim()
                if (canReturn) {
                    setResult(RESULT_OK, replyWith(text.ifBlank { raw }))
                } else {
                    notifyResult(text.isNotBlank(), text.ifBlank { getString(R.string.share_failed) })
                }
                finish()
            }
        }.start()
    }

    private fun replyWith(s: String): Intent {
        val r = Intent()
        r.putExtra(Intent.EXTRA_PROCESS_TEXT, s)
        return r
    }

    private fun buildPrompt(raw: String): String {
        val sep = System.lineSeparator()
        return getString(R.string.share_prompt_prefix) + sep + sep + raw
    }

    private fun notifyResult(ok: Boolean, text: String) {
        val title = getString(if (ok) R.string.share_done_title else R.string.share_fail_title)
        try {
            AndroidAPI.notify(title, text)
        } catch (e: Throwable) {
            toast(text)
        }
    }

    private fun toast(msg: String) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
        }
    }

    override fun onDestroy() {
        timer?.let { handler.removeCallbacks(it) }
        super.onDestroy()
    }

    companion object {
        // Caller waits on this; 20s balances long text against UX.
        private const val TIMEOUT_MS = 20_000L
    }
}