package de.ostlertekker.ultraiptv.core.i18n

import android.content.Context
import org.json.JSONObject

/**
 * Mehrsprachigkeit: Deutsch ist die Ausgangssprache (Schlüssel = deutscher Text).
 * Übersetzungen liegen als JSON unter assets/i18n/<sprache>.json; fehlt ein Eintrag, bleibt der deutsche Text.
 * Die Sprache wird beim App-Start festgelegt (Einstellung, sonst Gerätesprache, sonst Deutsch).
 */
object I18n {
    /** Unterstützte Sprachen: Kürzel → Name in der jeweiligen Sprache. */
    val LANGUAGES = listOf("de" to "Deutsch", "en" to "English", "tr" to "Türkçe", "pl" to "Polski", "es" to "Español", "fr" to "Français")
    private const val PREFS = "i18n"
    private const val KEY = "lang"

    @Volatile var lang: String = "de"; private set
    @Volatile private var table: Map<String, String> = emptyMap()

    /** Gewählte Einstellung: "system" oder ein Sprachkürzel. */
    fun setting(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "system") ?: "system"

    fun save(ctx: Context, value: String) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value).commit() }

    fun init(ctx: Context) {
        val s = setting(ctx)
        val sys = java.util.Locale.getDefault().language
        lang = when {
            s != "system" && LANGUAGES.any { it.first == s } -> s
            LANGUAGES.any { it.first == sys } -> sys
            else -> "de"
        }
        table = if (lang == "de") emptyMap() else load(ctx, lang)
    }

    private fun load(ctx: Context, code: String): Map<String, String> = runCatching {
        val o = JSONObject(ctx.assets.open("i18n/$code.json").bufferedReader().use { it.readText() })
        buildMap(o.length()) { o.keys().forEach { k -> o.optString(k).takeIf { it.isNotEmpty() }?.let { put(k, it) } } }
    }.getOrDefault(emptyMap())

    fun t(s: String): String = table[s] ?: s

    fun f(tpl: String, args: Array<out Any?>): String {
        var r = table[tpl] ?: tpl
        args.forEachIndexed { i, v -> r = r.replace("{" + i + "}", v.toString()) }
        return r
    }

    /**
     * App neu starten, damit die neue Sprache überall greift: ein kleiner Hilfsprozess startet die App neu,
     * nachdem dieser Prozess beendet ist (Fire TV erlaubt keinen Neustart per Wecker aus dem Hintergrund).
     */
    fun restart(activity: android.app.Activity) {
        val i = android.content.Intent(activity, RestartActivity::class.java)
            .putExtra(RestartActivity.EXTRA_PID, android.os.Process.myPid())
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(i)
        // Der Hilfsprozess beendet diesen Prozess – hier nur die Oberfläche schließen
        activity.finishAffinity()
    }
}

/** Übersetzt einen deutschen Oberflächentext. */
fun tr(s: String): String = I18n.t(s)

/** Übersetzt eine Vorlage mit Platzhaltern {0}, {1} … */
fun trf(tpl: String, vararg args: Any?): String = I18n.f(tpl, args)
