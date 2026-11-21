package de.ostlertekker.ultraiptv.core.notify

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * „Nach dem Gerätestart automatisch öffnen“.
 * Android 9 und älter (u. a. Fire OS 6/7): direkt möglich. Ab Android 10 erlaubt das System Apps den Start aus dem
 * Hintergrund nur mit der Berechtigung „Über anderen Apps einblenden“ – die App fragt sie in den Einstellungen an.
 */
object AutoStart {
    private const val PREFS = "autostart"

    fun isEnabled(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on", false)
    fun setEnabled(ctx: Context, on: Boolean) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", on).apply()

    /** Darf die App nach dem Start aus dem Hintergrund ihr Fenster öffnen? */
    fun canLaunch(ctx: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(ctx)

    /** Öffnet die Systemseite für „Über anderen Apps einblenden“. false = Gerät bietet keine solche Seite. */
    fun openPermission(ctx: Context): Boolean {
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")),
        )
        for (i in intents) {
            if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return true
        }
        return false
    }

    // ------------------------------------------------------------ Protokoll (nur Zeitpunkte und Ergebnisse, keine Daten)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun log(ctx: Context, msg: String) {
        val t = java.text.SimpleDateFormat("dd.MM. HH:mm:ss", java.util.Locale.GERMANY).format(java.util.Date())
        val old = prefs(ctx).getString("log", "").orEmpty().lines().filter { it.isNotBlank() }
        prefs(ctx).edit().putString("log", (old + "$t  $msg").takeLast(12).joinToString("\n")).commit()
        android.util.Log.i("UltraAutoStart", msg)
    }

    fun readLog(ctx: Context): String = prefs(ctx).getString("log", "").orEmpty()
    fun clearLog(ctx: Context) = prefs(ctx).edit().remove("log").apply()

    /** Von der Hauptansicht bei jedem Anzeigen gesetzt – damit lässt sich prüfen, ob der Start geklappt hat. */
    @Volatile var lastResumeMs = 0L
    fun markResumed() { lastResumeMs = System.currentTimeMillis() }

    private fun launchIntent(ctx: Context): Intent? {
        val i = ctx.packageManager.getLeanbackLaunchIntentForPackage(ctx.packageName)
            ?: ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return null
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
    }

    /** Weg 1: direkter Start. */
    fun launch(ctx: Context): Boolean {
        val i = launchIntent(ctx) ?: return false
        return runCatching { ctx.startActivity(i) }.onFailure { log(ctx, "Start abgelehnt: ${it.javaClass.simpleName}") }.isSuccess
    }

    /** Weg 2 (Android 14+): Start über eigenen PendingIntent mit ausdrücklicher Hintergrund-Start-Freigabe. */
    private fun launchViaPendingIntent(ctx: Context): Boolean {
        val i = launchIntent(ctx) ?: return false
        return runCatching {
            val pi = android.app.PendingIntent.getActivity(ctx, 4711, i,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            if (Build.VERSION.SDK_INT >= 34) {
                val opts = android.app.ActivityOptions.makeBasic()
                @Suppress("DEPRECATION")
                opts.setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pi.send(ctx, 0, null, null, null, null, opts.toBundle())
            } else pi.send()
        }.onFailure { log(ctx, "Start (Weg 2) abgelehnt: ${it.javaClass.simpleName}") }.isSuccess
    }

    /**
     * Start aus dem Hintergrund mit sichtbarem Mini-Overlay: Neuere Android-Versionen (u. a. 14/15) lassen den Start mit der
     * Overlay-Berechtigung nur zu, wenn die App gerade ein Overlay-Fenster zeigt. Das 1×1-Pixel-Fenster ist unsichtbar
     * und wird nach wenigen Sekunden wieder entfernt. Danach wird geprüft, ob die App wirklich sichtbar wurde.
     */
    fun launchFromBackground(ctx: Context, reason: String) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val started = System.currentTimeMillis()
        main.post {
            var view: android.view.View? = null
            val wm = ctx.getSystemService(android.view.WindowManager::class.java)
            val overlayOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && overlayOk && wm != null) {
                runCatching {
                    val v = android.view.View(ctx)
                    val lp = android.view.WindowManager.LayoutParams(1, 1,
                        android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        android.graphics.PixelFormat.TRANSLUCENT)
                    wm.addView(v, lp); view = v
                }.onFailure { log(ctx, "Mini-Overlay nicht möglich: ${it.javaClass.simpleName}") }
            }
            log(ctx, "$reason · Android ${Build.VERSION.RELEASE} · Berechtigung ${if (overlayOk) "ja" else "NEIN"} · Overlay ${if (view != null) "ja" else "nein"}")
            fun visible() = lastResumeMs >= started
            main.postDelayed({ launch(ctx) }, 300)
            // Weg 2 bzw. zweiter Versuch, falls der Startbildschirm des Geräts sich noch nach vorne geschoben hat
            main.postDelayed({ if (!visible()) { if (Build.VERSION.SDK_INT >= 34) launchViaPendingIntent(ctx) else launch(ctx) } }, 3_000)
            main.postDelayed({ if (!visible()) launch(ctx) }, 6_000)
            main.postDelayed({
                view?.let { v -> runCatching { wm?.removeView(v) } }
                log(ctx, if (visible()) "✓ App wurde geöffnet" else "✕ App nicht nach vorne gekommen – vom System blockiert")
            }, 9_000)
        }
    }
}
