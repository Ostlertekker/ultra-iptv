package de.ostlertekker.ultraiptv.core.diagnostics

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import de.ostlertekker.ultraiptv.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Stabilitäts-Wächter:
 *  • speichert jeden Absturz (Stacktrace) dauerhaft,
 *  • erkennt Hänger des UI-Threads (> 4 s keine Reaktion) und speichert, WO er hängt.
 * Beides ist unter Einstellungen › Diagnose sichtbar – so lässt sich jeder Fehler eindeutig finden.
 * Es werden keine Zugangsdaten gespeichert (nur Klassen-/Methodennamen).
 */
object StabilityGuard {
    private const val CRASH = "last_crash.txt"
    private const val FREEZE = "last_freeze.txt"
    private const val FREEZE_MS = 4_000L

    @Volatile private var dir: File? = null
    @Volatile private var lastPong = SystemClock.uptimeMillis()
    @Volatile private var reportedThisFreeze = false

    fun install(context: Context) {
        dir = context.filesDir
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(CRASH, "Absturz in Thread „${thread.name}“\n\n" + stack(error)) }
            previous?.uncaughtException(thread, error)
        }
        startFreezeWatchdog()
    }

    private fun startFreezeWatchdog() {
        val main = Handler(Looper.getMainLooper())
        val ping = Runnable { lastPong = SystemClock.uptimeMillis(); reportedThisFreeze = false }
        Thread({
            while (true) {
                main.post(ping)
                try { Thread.sleep(1_000) } catch (_: InterruptedException) { return@Thread }
                val stalled = SystemClock.uptimeMillis() - lastPong
                if (stalled > FREEZE_MS && !reportedThisFreeze) {
                    reportedThisFreeze = true
                    val trace = Looper.getMainLooper().thread.stackTrace.joinToString("\n") { "    at $it" }
                    runCatching { write(FREEZE, "UI-Thread reagiert seit ${stalled / 1000} s nicht.\nHängt hier:\n$trace") }
                }
            }
        }, "ultra-freeze-watchdog").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    private fun stack(t: Throwable): String = StringWriter().also { t.printStackTrace(PrintWriter(it)) }.toString()

    private fun write(name: String, body: String) {
        val d = dir ?: return
        val header = "ULTRA IPTV ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY).format(Date()) + "\n\n"
        File(d, name).writeText((header + body).take(60_000))
    }

    fun lastCrash(context: Context): String? = File(context.filesDir, CRASH).takeIf { it.exists() }?.readText()
    fun lastFreeze(context: Context): String? = File(context.filesDir, FREEZE).takeIf { it.exists() }?.readText()
    fun clear(context: Context) { File(context.filesDir, CRASH).delete(); File(context.filesDir, FREEZE).delete() }
}
