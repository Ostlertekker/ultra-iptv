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

    /**
     * Intro-Sound passend zur Animation: weicher Sub-Puls beim Aufbau von „ULTRA IPTV“, ein kurzes Luft-Whoosh, während
     * „PRO“ hereinfliegt, und beim Landen ein heller, glockiger „Bling“ mit Glitzer-Ausklang. Alles synthetisiert.
     */
    fun intro(short: Boolean): ShortArray {
        val k = if (short) 0.62f else 1f
        val pulseAt = 0.35f * k
        val whooshFrom = 1.55f * k
        val land = 1.95f * k
        val total = land + 2.0f
        val n = (RATE * total).toInt()
        val buf = FloatArray(n)
        val rnd = Random(11)
        var lp = 0f; var hp = 0f; var prev = 0f
        for (i in 0 until n) {
            val t = i.toFloat() / RATE
            var v = 0f
            // 1) Weicher Sub-Puls (Aufbau des Schriftzugs)
            if (t >= pulseAt) {
                val d = t - pulseAt
                val f = 52f + 30f * exp(-d * 9f)
                v += sin(2 * PI * f * d).toFloat() * 0.55f * exp(-d * 3.2f) * minOf(1f, d / 0.02f)
            }
            // 2) Whoosh: gefiltertes Rauschen, das bis zur Landung heller und lauter wird
            if (t in whooshFrom..(land + 0.05f)) {
                val x = ((t - whooshFrom) / (land - whooshFrom)).coerceIn(0f, 1f)
                val noise = rnd.nextFloat() * 2 - 1
                lp += (0.04f + 0.5f * x * x) * (noise - lp)
                hp = lp - prev; prev = lp
                v += (lp * 0.5f + hp * 1.2f) * x * x * 0.55f
            }
            // 3) Bling: Glockenklang mit leicht unharmonischen Teiltönen + Schwebung
            if (t >= land) {
                val d = t - land
                val att = minOf(1f, d / 0.004f)
                val f0 = 1975.5f // H6
                val bell = sin(2 * PI * f0 * d).toFloat() * exp(-d * 2.6f) +
                    0.55f * sin(2 * PI * f0 * 1.5 * d).toFloat() * exp(-d * 3.4f) +
                    0.35f * sin(2 * PI * f0 * 2.76 * d).toFloat() * exp(-d * 5.5f) +
                    0.22f * sin(2 * PI * f0 * 4.07 * d).toFloat() * exp(-d * 8f) +
                    0.4f * sin(2 * PI * (f0 * 1.003f) * d).toFloat() * exp(-d * 2.2f)
                v += bell * 0.32f * att
                // Kurzer heller Anschlag
                v += (rnd.nextFloat() * 2 - 1) * 0.18f * exp(-d * 60f)
                // Glitzer: kleine, zufällig gestreute Sinus-Funken
                val sp = exp(-d * 2.2f)
                v += sp * 0.05f * (sin(2 * PI * 3951 * d).toFloat() * (0.5f + 0.5f * sin(2 * PI * 9 * d).toFloat()) +
                    sin(2 * PI * 5274 * d).toFloat() * (0.5f + 0.5f * sin(2 * PI * 13 * d + 1).toFloat()))
                // Tiefer Körper unter dem Bling
                v += sin(2 * PI * 98 * d).toFloat() * 0.25f * exp(-d * 5f)
            }
            buf[i] = v
        }
        // Einfacher Hall (zwei Rückkopplungs-Verzögerungen) für Raum und Glanz
        for ((dl, g) in listOf(0.083f to 0.28f, 0.127f to 0.2f)) {
            val ds = (RATE * dl).toInt()
            for (i in ds until n) buf[i] += buf[i - ds] * g
        }
        val peak = buf.maxOf { abs(it) }.coerceAtLeast(0.001f)
        val fade = (RATE * 0.4f).toInt()
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

    /** Eigener Audio-Thread: AudioTrack-Systemaufrufe blockieren nie die Fokus-Navigation. */
    private val audioThread = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "ultra-ui-sound").apply { priority = Thread.NORM_PRIORITY - 1 } }
    @Volatile private var pending = 0

    private fun fire(t: AudioTrack?) {
        if (!enabled || suppressed || t == null) return
        if (pending > 2) return                       // bei Dauer-Scrollen Klänge nicht aufstauen
        pending++
        audioThread.execute {
            try {
                if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
                t.reloadStaticData()
                t.setVolume(volume)
                t.play()
            } catch (_: Exception) {
            } finally {
                pending--
            }
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

    /**
     * Wie [playIntro], kehrt aber erst zurück, wenn der Klang tatsächlich startet – so laufen Bild und Ton
     * synchron (der „Bling“ fällt genau auf die Landung des PRO-Abzeichens).
     */
    suspend fun playIntroAligned(short: Boolean) {
        val data = withContext(Dispatchers.Default) { Synth.intro(short) }
        val t = make(data) ?: return
        try { t.setVolume(maxOf(volume, 0.6f)); t.play() } catch (_: Exception) { runCatching { t.release() }; return }
        scope.launch {
            delay((data.size * 1000L / Synth.RATE) + 300)
            runCatching { t.stop() }; t.release()
        }
    }

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
