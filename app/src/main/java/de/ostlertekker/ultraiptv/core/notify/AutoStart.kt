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

    fun launch(ctx: Context) {
        val i = ctx.packageManager.getLeanbackLaunchIntentForPackage(ctx.packageName)
            ?: ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching { ctx.startActivity(i) }.onFailure { android.util.Log.w("UltraAutoStart", "Start abgelehnt: ${it.message}") }
    }

    /**
     * Start aus dem Hintergrund mit sichtbarem Mini-Overlay: Neuere Android-Versionen (u. a. 14/15) lassen den Start mit der
     * Overlay-Berechtigung nur zu, wenn die App gerade ein Overlay-Fenster zeigt. Das 1×1-Pixel-Fenster ist unsichtbar
     * und wird nach wenigen Sekunden wieder entfernt.
     */
    fun launchFromBackground(ctx: Context) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        main.post {
            var view: android.view.View? = null
            val wm = ctx.getSystemService(android.view.WindowManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Settings.canDrawOverlays(ctx) && wm != null) {
                runCatching {
                    val v = android.view.View(ctx)
                    val lp = android.view.WindowManager.LayoutParams(1, 1,
                        android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        android.graphics.PixelFormat.TRANSLUCENT)
                    wm.addView(v, lp); view = v
                }.onFailure { android.util.Log.w("UltraAutoStart", "Overlay nicht möglich: ${it.message}") }
            }
            main.postDelayed({ launch(ctx) }, 300)
            // Zweiter Versuch, falls der Startbildschirm des Geräts sich danach noch nach vorne geschoben hat
            main.postDelayed({ launch(ctx) }, 4_000)
            main.postDelayed({ view?.let { v -> runCatching { wm?.removeView(v) } } }, 6_000)
        }
    }
}
