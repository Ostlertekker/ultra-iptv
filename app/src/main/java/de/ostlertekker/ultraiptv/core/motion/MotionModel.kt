package de.ostlertekker.ultraiptv.core.motion

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.put
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// =====================================================================
// CRAZY MOTION ENGINE – Modell (rein Kotlin, ohne Android → unit-testbar)
// =====================================================================

/** Kategorie, nach der Animationen einzeln aktiviert/deaktiviert werden. */
interface MotionAnim {
    val id: String
    val label: String
    /** Aufwändig (Partikel/Splitter) – wird auf schwacher Hardware vereinfacht. */
    val heavy: Boolean
}

enum class EntranceAnim(override val label: String, val baseMs: Int, override val heavy: Boolean = false) : MotionAnim {
    GRAVITY_DROP("Schwerkraft-Fall", 900),
    EXPLOSION("Explosions-Auftritt", 650, true),
    HOLOGRAM("Hologramm-Erscheinen", 700),
    FLIP_3D("3D Flip", 600),
    CYBER_GLITCH("Cyber-Störung", 650, true),
    LIQUID_MORPH("Flüssige Verwandlung", 800),
    BLACK_HOLE("Schwarzes Loch", 850, true),
    METEOR_SHOWER("Meteoritenschauer", 750, true),
    DOMINO("Domino-Effekt", 550),
    MAGNETIC_PULL("Magnetischer Sog", 700),
    SHATTER_GLASS("Zerspringendes Glas", 800, true),
    PORTAL("Portal-Auftritt", 650),
    MATRIX_RAIN("Matrix-Regen", 850, true),
    ELASTIC_BOUNCE("Elastisches Federn", 750);

    override val id: String get() = "in_$name"
}

enum class FocusEffect(override val label: String, override val heavy: Boolean = false) : MotionAnim {
    MAGNETIC("Magnetischer Fokus"),
    TILT_3D("3D Tilt"),
    HOVER_EXPLOSION("Schwebe-Explosion", true),
    ELASTIC("Elastischer Fokus"),
    DEPTH_SHIFT("Tiefenverschiebung"),
    PULSE_GLOW("Pulsierendes Leuchten"),
    PERSPECTIVE_ZOOM("Perspektiv-Zoom"),
    TRAIL("Leuchtspur", true);

    override val id: String get() = "focus_$name"
}

enum class PageTransition(override val label: String, val baseMs: Int, override val heavy: Boolean = false) : MotionAnim {
    PORTAL("Portal-Übergang", 650),
    SHATTER("Splitter-Übergang", 750, true),
    WARP_SPEED("Warp-Sprung", 700, true),
    LIQUID("Flüssiger Übergang", 750),
    GLITCH("Störungs-Übergang", 450),
    ZOOM_TUNNEL("Zoom-Tunnel", 650),
    CINEMATIC("Kino-Enthüllung", 800);

    override val id: String get() = "page_$name"
}

/** Ausgangsanimationen der verlassenen Seite (GPU-Transformationen der Navigation). */
enum class ExitAnim(val label: String) {
    FADE("Ausblenden"), SHRINK("Schrumpfen"), ZOOM_OUT("Wegzoomen"), SLIDE_LEFT("Nach links gleiten"),
    GRAVITY_FALL("Herunterfallen"), DISSOLVE("Langsam auflösen"), NONE("Keine"),
}

/** Gespeicherte Konfiguration der Engine. */
data class MotionConfig(
    val enabled: Boolean = true,
    val reduced: Boolean = false,
    val autoSimplify: Boolean = true,
    /** null = zufällig aus allen aktivierten Eingangsanimationen. */
    val entrance: EntranceAnim? = EntranceAnim.DOMINO,
    val cardEntrance: Boolean = true,
    val focus: FocusEffect = FocusEffect.MAGNETIC,
    val pageEnter: PageTransition? = PageTransition.PORTAL,
    val pageExit: ExitAnim = ExitAnim.FADE,
    /** Effekt beim Senderwechsel im Player (läuft über dem Video, verzögert nichts). */
    val zapEffect: PageTransition? = PageTransition.GLITCH,
    val playerEnter: PageTransition? = PageTransition.CINEMATIC,
    val speed: Float = 1f,
    val intensity: Float = 1f,
    val particles: Boolean = true,
    val disabled: Set<String> = emptySet(),
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("enabled", enabled); put("reduced", reduced); put("autoSimplify", autoSimplify)
        put("entrance", entrance?.name ?: RANDOM); put("cardEntrance", cardEntrance); put("focus", focus.name)
        put("pageEnter", pageEnter?.name ?: NONE); put("pageExit", pageExit.name)
        put("zap", zapEffect?.name ?: NONE); put("playerEnter", playerEnter?.name ?: NONE)
        put("speed", speed); put("intensity", intensity); put("particles", particles)
        put("disabled", buildJsonArray { disabled.sorted().forEach { add(JsonPrimitive(it)) } })
    }

    companion object {
        const val RANDOM = "RANDOM"
        const val NONE = "NONE"

        fun fromJson(text: String?): MotionConfig {
            if (text.isNullOrBlank()) return MotionConfig()
            val o = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return MotionConfig()
            return fromJson(o)
        }

        fun fromJson(o: JsonObject): MotionConfig {
            val d = MotionConfig()
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            fun b(k: String, def: Boolean) = (o[k] as? JsonPrimitive)?.booleanOrNull ?: def
            fun f(k: String, def: Float) = (o[k] as? JsonPrimitive)?.floatOrNull ?: def
            return MotionConfig(
                enabled = b("enabled", d.enabled), reduced = b("reduced", d.reduced), autoSimplify = b("autoSimplify", d.autoSimplify),
                entrance = s("entrance").let { v -> if (v == RANDOM) null else EntranceAnim.entries.firstOrNull { it.name == v } ?: d.entrance },
                cardEntrance = b("cardEntrance", d.cardEntrance),
                focus = FocusEffect.entries.firstOrNull { it.name == s("focus") } ?: d.focus,
                pageEnter = s("pageEnter").let { v -> if (v == NONE) null else PageTransition.entries.firstOrNull { it.name == v } ?: d.pageEnter },
                pageExit = ExitAnim.entries.firstOrNull { it.name == s("pageExit") } ?: d.pageExit,
                zapEffect = s("zap").let { v -> if (v == NONE) null else PageTransition.entries.firstOrNull { it.name == v } ?: d.zapEffect },
                playerEnter = s("playerEnter").let { v -> if (v == NONE) null else PageTransition.entries.firstOrNull { it.name == v } ?: d.playerEnter },
                speed = f("speed", d.speed).coerceIn(0.25f, 3f),
                intensity = f("intensity", d.intensity).coerceIn(0f, 2f),
                particles = b("particles", d.particles),
                disabled = (o["disabled"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet() ?: emptySet(),
            )
        }
    }
}

data class MotionProfile(val name: String, val config: MotionConfig, val builtIn: Boolean = false)

object MotionPresets {
    val all = listOf(
        MotionProfile("Ruhig", MotionConfig(entrance = EntranceAnim.FLIP_3D, focus = FocusEffect.ELASTIC, pageEnter = PageTransition.CINEMATIC,
            zapEffect = null, playerEnter = null, speed = 1.3f, intensity = 0.5f, particles = false), true),
        MotionProfile("Cinematic", MotionConfig(entrance = EntranceAnim.HOLOGRAM, focus = FocusEffect.DEPTH_SHIFT, pageEnter = PageTransition.CINEMATIC,
            pageExit = ExitAnim.DISSOLVE, zapEffect = PageTransition.ZOOM_TUNNEL, playerEnter = PageTransition.CINEMATIC, speed = 0.9f), true),
        MotionProfile("Crazy", MotionConfig(entrance = null, focus = FocusEffect.HOVER_EXPLOSION, pageEnter = PageTransition.SHATTER,
            pageExit = ExitAnim.GRAVITY_FALL, zapEffect = PageTransition.WARP_SPEED, playerEnter = PageTransition.PORTAL, intensity = 1.4f), true),
        MotionProfile("Gaming", MotionConfig(entrance = EntranceAnim.CYBER_GLITCH, focus = FocusEffect.TRAIL, pageEnter = PageTransition.GLITCH,
            pageExit = ExitAnim.ZOOM_OUT, zapEffect = PageTransition.GLITCH, playerEnter = PageTransition.WARP_SPEED, speed = 1.2f), true),
    )

    fun profilesToJson(list: List<MotionProfile>): String = buildJsonArray {
        list.filter { !it.builtIn }.forEach { p -> add(buildJsonObject { put("name", p.name); put("config", p.config.toJson()) }) }
    }.toString()

    fun profilesFromJson(text: String?): List<MotionProfile> {
        if (text.isNullOrBlank()) return emptyList()
        val arr = runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val cfg = (o["config"] as? JsonObject)?.let { MotionConfig.fromJson(it) } ?: return@mapNotNull null
            MotionProfile(name, cfg)
        }
    }
}

/**
 * Effektive, laufzeitfertige Einstellung: berücksichtigt "Reduziert", deaktivierte Einzelanimationen
 * und die automatische Vereinfachung auf schwacher Hardware.
 */
data class MotionSpec(
    val enabled: Boolean,
    val entrance: EntranceAnim?,
    val randomEntrance: Boolean,
    val enabledEntrances: List<EntranceAnim>,
    val cardEntrance: Boolean,
    val focus: FocusEffect?,
    val pageEnter: PageTransition?,
    val pageExit: ExitAnim,
    val zapEffect: PageTransition?,
    val playerEnter: PageTransition?,
    val speed: Float,
    val intensity: Float,
    val particles: Boolean,
    /** Vereinfachte Darstellung: weniger Partikel/Splitter, kürzere Laufzeit. */
    val lite: Boolean,
) {
    fun durationMs(base: Int): Int = ((base / speed) * (if (lite) 0.75f else 1f)).toInt().coerceIn(120, 3000)

    /** Partikelanzahl unter Berücksichtigung von Lite-Modus und Schalter. */
    fun particleCount(full: Int): Int = if (!particles) 0 else if (lite) (full / 3).coerceAtLeast(1) else full

    /** Eingangsanimation für ein Element (bei "Zufall" deterministisch pro Schlüssel). */
    fun entranceFor(seed: Int): EntranceAnim? = when {
        !enabled -> null
        randomEntrance -> enabledEntrances.takeIf { it.isNotEmpty() }?.let { it[abs(seed) % it.size] }
        else -> entrance
    }

    companion object {
        val OFF = MotionSpec(false, null, false, emptyList(), false, null, null, ExitAnim.NONE, null, null, 1f, 0f, false, true)

        fun resolve(c: MotionConfig, weakHardware: Boolean, systemReduceMotion: Boolean): MotionSpec {
            if (!c.enabled) return OFF
            if (c.reduced || systemReduceMotion) {
                // Reduzierte Animationen: nur ruhige, kurze Effekte ohne Partikel
                return MotionSpec(true, null, false, emptyList(), false, FocusEffect.ELASTIC.takeIf { it.id !in c.disabled },
                    PageTransition.CINEMATIC.takeIf { c.pageEnter != null }, ExitAnim.FADE, null, null, 1.5f, 0.4f, false, true)
            }
            val lite = weakHardware && c.autoSimplify
            fun <T : MotionAnim> ok(a: T?): T? = a?.takeIf { it.id !in c.disabled }
            val enabledEntrances = EntranceAnim.entries.filter { it.id !in c.disabled && !(lite && it.heavy && it == EntranceAnim.SHATTER_GLASS) }
            return MotionSpec(
                enabled = true,
                entrance = ok(c.entrance),
                randomEntrance = c.entrance == null,
                enabledEntrances = enabledEntrances,
                cardEntrance = c.cardEntrance,
                focus = ok(c.focus),
                pageEnter = ok(c.pageEnter),
                pageExit = c.pageExit,
                zapEffect = ok(c.zapEffect),
                playerEnter = ok(c.playerEnter),
                speed = c.speed.coerceIn(0.25f, 3f),
                intensity = c.intensity.coerceIn(0f, 2f),
                particles = c.particles,
                lite = lite,
            )
        }
    }
}

/** Physikalische Kurven (analytisch, deterministisch → GPU-Transformation pro Frame ohne Allokation). */
object MotionMath {
    fun clamp01(t: Float) = t.coerceIn(0f, 1f)
    fun easeOutCubic(t: Float): Float { val x = 1 - clamp01(t); return 1 - x * x * x }
    fun easeInCubic(t: Float): Float { val x = clamp01(t); return x * x * x }
    fun easeOutExpo(t: Float): Float = if (t >= 1f) 1f else 1 - 2f.pow(-10 * clamp01(t))
    fun easeOutBack(t: Float, s: Float = 1.7f): Float { val x = clamp01(t) - 1; return 1 + (s + 1) * x * x * x + s * x * x }

    /**
     * Freier Fall mit Aufprall und Rückprall (Restitution [restitution]).
     * Liefert die zurückgelegte Strecke 0 → 1 (1 = am Boden). Mehrere Sprünge mit abnehmender Höhe.
     */
    fun gravityBounce(t: Float, restitution: Float = 0.45f, bounces: Int = 3): Float {
        val x = clamp01(t)
        // Fallzeit 1, jeder Sprung dauert 2*sqrt(h) relativ, Gesamtdauer normiert
        val segs = ArrayList<Float>()
        segs += 1f
        var h = 1f
        repeat(bounces) { h *= restitution * restitution; segs += 2f * sqrt(h) }
        val total = segs.sum()
        var time = x * total
        if (time <= segs[0]) return time * time        // s = t² (normiert)
        time -= segs[0]
        h = 1f
        for (i in 1 until segs.size) {
            h *= restitution * restitution
            val d = segs[i]
            if (time <= d) {
                val half = d / 2f
                val u = (time - half) / half          // -1 .. 1
                return 1f - h * (1f - u * u)
            }
            time -= d
        }
        return 1f
    }

    /** Gedämpfte Feder: 0 → 1 mit Überschwingen (Elastizität). */
    fun elastic(t: Float, damping: Float = 6f, freq: Float = 12f): Float {
        val x = clamp01(t)
        if (x >= 1f) return 1f
        return 1f - exp(-damping * x) * cos(freq * x)
    }

    /** Bounce-Out (Domino/Aufschlag). */
    fun bounceOut(t: Float): Float {
        var x = clamp01(t)
        val n = 7.5625f; val d = 2.75f
        return when {
            x < 1 / d -> n * x * x
            x < 2 / d -> { x -= 1.5f / d; n * x * x + 0.75f }
            x < 2.5 / d -> { x -= 2.25f / d; n * x * x + 0.9375f }
            else -> { x -= 2.625f / d; n * x * x + 0.984375f }
        }
    }

    /** Deterministischer Pseudozufall 0..1. */
    fun hash(a: Int, b: Int): Float {
        var h = a * 374761393 + b * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0x7fffffff) / 2147483647f
    }

    fun sinPi(x: Float) = sin(PI.toFloat() * x)
}
