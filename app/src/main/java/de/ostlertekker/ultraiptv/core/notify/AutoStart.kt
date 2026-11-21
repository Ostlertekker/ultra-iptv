package de.ostlertekker.ultraiptv.core.notify

import android.app.ActivityOptions
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Zentraler Autostart-Manager.
 *
 * Ablauf nach dem Gerätestart:
 *  1. [BootReceiver] erhält BOOT_COMPLETED (bzw. herstellerspezifische Varianten) – einmal pro Boot ([claimBoot]).
 *  2. Ist „Nach Gerätestart öffnen“ aktiv, wird geprüft, ob Android den Start aus dem Hintergrund erlaubt ([canLaunch]).
 *     Ab Android 10 ist dafür die Sonderberechtigung „Über anderen Apps einblenden“ nötig (offizielle Ausnahme der
 *     Hintergrund-Startsperre). Ab Android 15 muss zusätzlich gerade ein Overlay-Fenster sichtbar sein → 1×1-Pixel-Fenster.
 *  3. [launchFromBackground] startet die Hauptansicht (mehrere Wege, nur solange sie noch nicht sichtbar ist) und prüft
 *     anschließend, ob sie wirklich nach vorne kam. Ergebnis landet im Protokoll – keine Erfolgsanzeige ohne Nachweis.
 *  4. Die Hauptansicht erhält [EXTRA_AUTOSTART] und kümmert sich um Playlist und letzten Sender.
 *
 * Nicht möglich (Plattformgrenzen, werden in der Diagnose erklärt):
 *  - Nach „Beenden erzwingen“ bzw. durch Speicher-Bereinigungs-Apps ist eine App „gestoppt“ – Android stellt ihr dann
 *    keine Boot-Meldung mehr zu, bis sie einmal von Hand geöffnet wurde. Das gilt ebenso direkt nach der Erstinstallation.
 *  - Aufwachen aus dem Standby ist kein Gerätestart; Fire TV und die meisten Android-TV-Geräte melden dabei nichts.
 */
object AutoStart {
    private const val PREFS = "autostart"
    const val EXTRA_AUTOSTART = "ultra_autostart"

    // Einstellungen (SharedPreferences: im Boot-Empfänger synchron lesbar, ohne Datenbank/Hilt)
    const val K_MASTER = "master"            // Autostart-Funktionen insgesamt
    const val K_BOOT = "on"                  // IPTV-App nach Gerätestart öffnen
    const val K_LAST_CHANNEL = "last_channel" // letzten Sender automatisch wiedergeben
    const val K_PLAYLIST = "playlist"        // Playlist nach dem Autostart aktualisieren
    const val K_STANDBY = "standby"          // Wiedergabe nach Standby fortsetzen
    const val K_CRASH = "crash"              // nach Absturz neu öffnen

    private val DEFAULTS = mapOf(K_MASTER to true, K_BOOT to false, K_LAST_CHANNEL to false, K_PLAYLIST to false, K_STANDBY to true, K_CRASH to false)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun raw(ctx: Context, key: String): Boolean = prefs(ctx).getBoolean(key, DEFAULTS[key] ?: false)

    /** Wirksamer Wert: Ist der Hauptschalter aus, ist alles aus – nur „nach Standby fortsetzen“ fällt aufs normale Verhalten (an) zurück. */
    fun get(ctx: Context, key: String): Boolean = when {
        key == K_MASTER -> raw(ctx, K_MASTER)
        !raw(ctx, K_MASTER) -> key == K_STANDBY
        else -> raw(ctx, key)
    }
    fun stored(ctx: Context, key: String): Boolean = raw(ctx, key)
    fun set(ctx: Context, key: String, on: Boolean) { prefs(ctx).edit().putBoolean(key, on).apply() }

    fun isEnabled(ctx: Context) = get(ctx, K_BOOT)
    fun setEnabled(ctx: Context, on: Boolean) = set(ctx, K_BOOT, on)

    // ------------------------------------------------------------------------------------------ Plattform

    enum class Platform(val label: String) { FIRE_TV("Fire TV (Fire OS)"), GOOGLE_TV("Google TV"), ANDROID_TV("Android TV"), OTHER("Android") }

    fun platform(ctx: Context): Platform {
        val pm = ctx.packageManager
        return when {
            Build.MANUFACTURER.equals("Amazon", true) || pm.hasSystemFeature("amazon.hardware.fire_tv") -> Platform.FIRE_TV
            pm.hasSystemFeature("com.google.android.feature.GOOGLE_EXPERIENCE") && pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) &&
                runCatching { pm.getPackageInfo("com.google.android.apps.tv.launcherx", 0) }.isSuccess -> Platform.GOOGLE_TV
            pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) -> Platform.ANDROID_TV
            else -> Platform.OTHER
        }
    }

    /** Darf die App nach dem Start aus dem Hintergrund ihr Fenster öffnen? */
    fun canLaunch(ctx: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(ctx)

    /** Verständliche Hinweise für dieses Gerät (leer = nichts weiter nötig). */
    fun deviceHints(ctx: Context): List<String> {
        val out = ArrayList<String>()
        if (!canLaunch(ctx)) out += "Android ${Build.VERSION.RELEASE} erlaubt den automatischen Start nur mit der Berechtigung „Über anderen Apps einblenden“."
        when (platform(ctx)) {
            Platform.FIRE_TV -> out += "Fire TV: ULTRA IPTV nicht über „Beenden erzwingen“ schließen und keine Speicher-Bereinigungs-Apps verwenden – danach meldet Fire OS der App den Gerätestart nicht mehr, bis sie einmal von Hand geöffnet wurde."
            Platform.GOOGLE_TV, Platform.ANDROID_TV -> out += "Nach der Installation bzw. nach „Beenden erzwingen“ die App einmal von Hand öffnen – vorher stellt Android ihr keine Startmeldung zu."
            Platform.OTHER -> Unit
        }
        out += "Standby ist kein Neustart: Beim Aufwachen meldet das Gerät nichts, die App öffnet sich dann nicht von selbst."
        return out
    }

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

    // ------------------------------------------------------------------------------------------ Protokoll

    fun log(ctx: Context, msg: String) {
        val t = java.text.SimpleDateFormat("dd.MM. HH:mm:ss", java.util.Locale.GERMANY).format(java.util.Date())
        val old = prefs(ctx).getString("log", "").orEmpty().lines().filter { it.isNotBlank() }
        prefs(ctx).edit().putString("log", (old + "$t  $msg").takeLast(16).joinToString("\n")).commit()
        android.util.Log.i("UltraAutoStart", msg)
    }

    fun readLog(ctx: Context): String = prefs(ctx).getString("log", "").orEmpty()
    fun clearLog(ctx: Context) = prefs(ctx).edit().remove("log").apply()

    // ------------------------------------------------------------------------------------------ Sichtbarkeit

    /** Wird von UltraApp für jede Activity der App gesetzt. */
    @Volatile var lastResumeMs = 0L
    @Volatile var startedActivities = 0
    fun markResumed() { lastResumeMs = System.currentTimeMillis() }
    val inForeground: Boolean get() = startedActivities > 0

    // ------------------------------------------------------------------------------------------ Gerätestart

    /** Einmal pro Boot: mehrere Boot-Meldungen (z. B. QUICKBOOT + BOOT_COMPLETED) lösen nur einen Start aus. */
    fun claimBoot(ctx: Context): Boolean {
        val boot = runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)
        // Ohne Boot-Zähler: Zeitpunkt des Boots (auf ~1 Minute genau) als Kennung
        val id = if (boot >= 0) "c$boot" else "t" + ((System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()) / 60_000)
        val p = prefs(ctx)
        synchronized(this) {
            if (p.getString("boot_id", null) == id) return false
            p.edit().putString("boot_id", id).commit()
        }
        return true
    }

    private fun launchIntent(ctx: Context): Intent? {
        val i = ctx.packageManager.getLeanbackLaunchIntentForPackage(ctx.packageName)
            ?: ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return null
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra(EXTRA_AUTOSTART, true)
    }

    /** Ab Android 14: Ersteller-Freigabe für Hintergrundstarts ausdrücklich setzen (sonst gilt sie für PendingIntents nicht). */
    private fun creatorOptions(): Bundle? = if (Build.VERSION.SDK_INT >= 34) {
        @Suppress("DEPRECATION")
        ActivityOptions.makeBasic().setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle()
    } else null

    private fun senderOptions(): Bundle? = if (Build.VERSION.SDK_INT >= 34) {
        @Suppress("DEPRECATION")
        ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle()
    } else null

    private fun pending(ctx: Context, code: Int): PendingIntent? = launchIntent(ctx)?.let {
        PendingIntent.getActivity(ctx, code, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT, creatorOptions())
    }

    /** Weg 1: direkter Start. */
    private fun launchDirect(ctx: Context): Boolean {
        val i = launchIntent(ctx) ?: return false
        return runCatching { ctx.startActivity(i) }.onFailure { log(ctx, "Start abgelehnt: ${it.javaClass.simpleName}") }.isSuccess
    }

    /** Weg 2: eigener PendingIntent mit Freigabe von Ersteller und Absender (Android 14+). */
    private fun launchPending(ctx: Context): Boolean = runCatching {
        val pi = pending(ctx, 4711) ?: return false
        if (Build.VERSION.SDK_INT >= 34) pi.send(ctx, 0, null, null, null, null, senderOptions()) else pi.send()
    }.onFailure { log(ctx, "Start (Weg 2) abgelehnt: ${it.javaClass.simpleName}") }.isSuccess

    /**
     * Start aus dem Hintergrund. Das 1×1-Pixel-Overlay ist unsichtbar und wird nach wenigen Sekunden entfernt.
     * Weitere Versuche nur, solange die App noch nicht sichtbar ist (keine Doppelstarts).
     */
    fun launchFromBackground(ctx: Context, reason: String) {
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        val started = System.currentTimeMillis()
        main.post {
            var view: android.view.View? = null
            val wm = app.getSystemService(android.view.WindowManager::class.java)
            val allowed = canLaunch(app)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Settings.canDrawOverlays(app) && wm != null) {
                runCatching {
                    val v = android.view.View(app)
                    val lp = android.view.WindowManager.LayoutParams(1, 1,
                        android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        android.graphics.PixelFormat.TRANSLUCENT)
                    wm.addView(v, lp); view = v
                }.onFailure { log(app, "Mini-Overlay nicht möglich: ${it.javaClass.simpleName}") }
            }
            log(app, "$reason · ${platform(app).label} · Android ${Build.VERSION.RELEASE} · Berechtigung ${if (allowed) "ja" else "NEIN"} · Overlay ${if (view != null) "ja" else "nein"}")
            fun visible() = lastResumeMs >= started
            main.postDelayed({ if (!visible()) launchDirect(app) }, 300)
            main.postDelayed({ if (!visible()) launchPending(app) }, 3_000)
            main.postDelayed({ if (!visible()) launchDirect(app) }, 6_500)
            main.postDelayed({
                view?.let { v -> runCatching { wm?.removeView(v) } }
                log(app, if (visible()) "✓ App wurde geöffnet" else "✕ App kam nicht nach vorne – vom System blockiert")
            }, 10_000)
        }
    }

    // ------------------------------------------------------------------------------------------ Absturz

    /**
     * Nach einem Absturz im Vordergrund die App in ~2 s neu öffnen – höchstens 2-mal in 10 Minuten (keine Schleife).
     * Läuft im Absturz-Handler: nur einfache, schnelle Aufrufe.
     */
    fun onCrash(ctx: Context) {
        if (!get(ctx, K_CRASH) || !inForeground) return
        val now = System.currentTimeMillis()
        val p = prefs(ctx)
        val recent = p.getString("crashes", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }.filter { now - it < 600_000 }
        if (recent.size >= 2) { log(ctx, "Absturz – kein Neustart (bereits ${recent.size}× in 10 Minuten)"); return }
        p.edit().putString("crashes", (recent + now).joinToString(",")).commit()
        val pi = pending(ctx, 4712) ?: return
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + 2_000, pi) }
            .onSuccess { log(ctx, "Absturz – App wird neu geöffnet") }
            .onFailure { log(ctx, "Absturz – Neustart nicht möglich: ${it.javaClass.simpleName}") }
    }

    // ------------------------------------------------------------------------------------------ Standby

    @Volatile var lastScreenOffMs = 0L

    /** Bildschirm ging aus, nachdem die Wiedergabe angehalten wurde (bzw. kurz davor) → Rückkehr aus dem Standby. */
    fun wasStandbySince(stoppedAtMs: Long): Boolean = stoppedAtMs > 0 && lastScreenOffMs >= stoppedAtMs - 3_000

    /** Wird einmal in UltraApp registriert (nur solange die App läuft, kein Hintergrunddienst). */
    fun registerScreenState(ctx: Context) {
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action == Intent.ACTION_SCREEN_OFF) lastScreenOffMs = System.currentTimeMillis()
            }
        }
        runCatching {
            val f = android.content.IntentFilter(Intent.ACTION_SCREEN_OFF)
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else ctx.registerReceiver(r, f)
        }
    }
}
