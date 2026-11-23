package de.ostlertekker.ultraiptv.core.i18n

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Process

/**
 * Läuft in einem eigenen Prozess (":restart"): wartet, bis der alte App-Prozess beendet ist,
 * startet dann die App neu und beendet sich selbst. Wird nur beim Sprachwechsel benutzt.
 */
class RestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val oldPid = intent.getIntExtra(EXTRA_PID, -1)
        if (oldPid > 0 && oldPid != Process.myPid()) runCatching { Process.killProcess(oldPid) }
        window.decorView.postDelayed({
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(it)
            }
            finish()
            Runtime.getRuntime().exit(0)
        }, 350)
    }

    companion object {
        const val EXTRA_PID = "old_pid"
        fun isRestartProcess(): Boolean = runCatching {
            java.io.File("/proc/self/cmdline").readText().trim { it <= ' ' || it == '\u0000' }.endsWith(":restart")
        }.getOrDefault(false)
    }
}
