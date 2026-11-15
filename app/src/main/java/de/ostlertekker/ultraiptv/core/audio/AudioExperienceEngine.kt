package de.ostlertekker.ultraiptv.core.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Klangcharakter pro Theme (alle Klänge werden zur Laufzeit synthetisiert – keine Fremd-Samples). */
enum class SoundPreset(val base: Float, val bright: Float, val square: Float) {
    COSMIC(1320f, 1.6f, 0f), GOLD(988f, 2.0f, 0f), NEON(1175f, 1.5f, 0.45f), GLASS(1568f, 2.5f, 0f),
    AURORA(880f, 1.5f, 0f), OCEAN(660f, 1.25f, 0f), DIGITAL(1760f, 1.0f, 0.8f), SPACE(740f, 1.5f, 0.1f),
    CRYSTAL(2093f, 2.76f, 0f), FIRE(587f, 1.33f, 0.25f),
}

object Synth {
    const val RATE = 44_100

    fun blip(freq: Float, durMs: Int, decay: Float, square: Float, gain: Float): ShortArray {
        val n = RATE * durMs / 1000
        return ShortArray(n) { i ->
            val t = i.toFloat() / RATE
            val env = exp(-t * decay) * minOf(1f, i / 80f)
            val s = sin(2 * PI * freq * t).toFloat()
            val sq = if (s >= 0) 1f else -1f
            val v = (s * (1 - square) + sq * square * 0.5f) * env * gain
            (v.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
    }

    fun concat(vararg parts: ShortArray): ShortArray {
        val out = ShortArray(parts.sumOf { it.size }); var o = 0
        for (p in parts) { p.copyInto(out, o); o += p.size }
        return out
    }

    fun whoosh(durMs: Int, gain: Float): ShortArray {
        val n = RATE * durMs / 1000; val rnd = Random(7); var lp = 0f
        return ShortArray(n) { i ->
            val x = i.toFloat() / n
            val cutoff = 0.02f + 0.25f * x
            lp += cutoff * ((rnd.nextFloat() * 2 - 1) - lp)
            val env = sin(PI * x).toFloat()
            ((lp * env * gain * 2.2f).coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
    }

    /** Originaler Kino-Intro-Sound: Sub-Bass, elektronischer Aufbau, metallische Akzente, Impact, Schimmer. */
    fun intro(short: Boolean): ShortArray {
        val total = if (short) 1.8f else 4.6f
        val impactAt = if (short) 0.35f else 3.0f
        val n = (RATE * total).toInt()
        val buf = FloatArray(n)
        val rnd = Random(42)
        var lp = 0f
        var phaseBass = 0.0
        for (i in 0 until n) {
            val t = i.toFloat() / RATE
            var v = 0f
            if (!short) {
                // Sub-Bass-Anschwellen
                val bassEnv = ((t - 0.4f) / 2.6f).coerceIn(0f, 1f)
                phaseBass += 2 * PI * (36.0 + 10.0 * bassEnv) / RATE
                v += sin(phaseBass).toFloat() * 0.45f * bassEnv * if (t < impactAt) 1f else exp(-(t - impactAt) * 2.5f)
                // Gefilterter Rausch-Riser
                if (t in 0.5f..impactAt) {
                    val x = (t - 0.5f) / (impactAt - 0.5f)
                    lp += (0.01f + 0.3f * x * x) * ((rnd.nextFloat() * 2 - 1) - lp)
                    v += lp * 0.55f * x
                }
                // Metallische Akzente (unharmonische Teiltöne)
                for (hit in floatArrayOf(1.7f, 2.35f)) {
                    if (t >= hit) {
                        val d = t - hit
                        val e = exp(-d * 5f)
                        v += e * 0.12f * (sin(2 * PI * 740 * d).toFloat() + 0.6f * sin(2 * PI * 740 * 2.76 * d).toFloat() +
                            0.35f * sin(2 * PI * 740 * 5.4 * d).toFloat() + 0.2f * sin(2 * PI * 740 * 8.93 * d).toFloat())
                    }
                }
            }
            // Impact
            if (t >= impactAt) {
                val d = t - impactAt
                val f = 34f + 50f * exp(-d * 6f)
                v += sin(2 * PI * f * d).toFloat() * 0.9f * exp(-d * 2.8f)
                v += (rnd.nextFloat() * 2 - 1) * 0.35f * exp(-d * 22f)
                // Premium-Schimmer zum Abschluss
                val sh = exp(-d * 1.6f) * ((d) / 0.25f).coerceIn(0f, 1f)
                v += sh * 0.07f * (sin(2 * PI * 2093 * d).toFloat() + sin(2 * PI * 2637 * d).toFloat() + sin(2 * PI * 3136 * d).toFloat()) *
                    (0.75f + 0.25f * sin(2 * PI * 6 * d).toFloat())
            }
            buf[i] = v
        }
        // Normalisieren + Fade-out
        val peak = buf.maxOf { abs(it) }.coerceAtLeast(0.001f)
        val fade = (RATE * 0.25f).toInt()
        return ShortArray(n) { i ->
            val f = if (i > n - fade) (n - i).toFloat() / fade else 1f
            ((buf[i] / peak) * 0.85f * f * Short.MAX_VALUE).toInt().toShort()
        }
    }
}

/**
 * UI-Klänge (Fokus, Auswahl, Zurück, Übergang) und Intro-Sound. Klänge laufen über
 * USAGE_ASSISTANCE_SONIFICATION und werden nie während einer Videowiedergabe abgespielt.
 */
@Singleton
class AudioExperienceEngine @Inject constructor() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var enabled = true
    @Volatile private var volume = 0.5f
    @Volatile var suppressed = false
    private var preset: SoundPreset? = null
    private var focusTrack: AudioTrack? = null
    private var selectTrack: AudioTrack? = null
    private var backTrack: AudioTrack? = null
    private var transitionTrack: AudioTrack? = null
    private var lastFocus = 0L

    fun configure(enabled: Boolean, volume: Float, preset: SoundPreset) {
        this.enabled = enabled
        this.volume = volume.coerceIn(0f, 1f)
        if (enabled && preset != this.preset) {
            this.preset = preset
            scope.launch { build(preset) }
        }
    }

    @Synchronized
    private fun build(p: SoundPreset) {
        releaseTracks()
        focusTrack = make(Synth.blip(p.base * p.bright, 35, 90f, p.square, 0.35f))
        selectTrack = make(Synth.concat(Synth.blip(p.base, 45, 40f, p.square, 0.45f), Synth.blip(p.base * 1.5f, 80, 30f, p.square, 0.45f)))
        backTrack = make(Synth.concat(Synth.blip(p.base * 1.2f, 40, 45f, p.square, 0.4f), Synth.blip(p.base * 0.8f, 70, 35f, p.square, 0.4f)))
        transitionTrack = make(Synth.whoosh(200, 0.5f))
    }

    private fun make(data: ShortArray): AudioTrack? = try {
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(Synth.RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(data.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(data, 0, data.size)
        t
    } catch (e: Exception) { null }

    private fun fire(t: AudioTrack?) {
        if (!enabled || suppressed || t == null) return
        try {
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
            t.reloadStaticData()
            t.setVolume(volume)
            t.play()
        } catch (_: Exception) {
        }
    }

    fun focus() {
        val now = System.currentTimeMillis()
        if (now - lastFocus < 60) return
        lastFocus = now
        fire(focusTrack)
    }
    fun select() = fire(selectTrack)
    fun back() = fire(backTrack)
    fun transition() = fire(transitionTrack)

    /** Spielt den Intro-Sound ab (lautstärkeunabhängig vom UI-Schalter, aber mit eigener Einstellung). */
    fun playIntro(short: Boolean) {
        scope.launch {
            val data = withContext(Dispatchers.Default) { Synth.intro(short) }
            val t = make(data) ?: return@launch
            try {
                t.setVolume(maxOf(volume, 0.6f))
                t.play()
                delay((data.size * 1000L / Synth.RATE) + 300)
            } catch (_: Exception) {
            } finally {
                runCatching { t.stop() }; t.release()
            }
        }
    }

    @Synchronized
    private fun releaseTracks() {
        listOf(focusTrack, selectTrack, backTrack, transitionTrack).forEach { runCatching { it?.release() } }
        focusTrack = null; selectTrack = null; backTrack = null; transitionTrack = null
    }
}
