package de.ostlertekker.ultraiptv.core.device

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.PowerManager
import android.view.Display
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.AudioCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import de.ostlertekker.ultraiptv.core.model.Accuracy
import de.ostlertekker.ultraiptv.core.model.AnimationProfile
import de.ostlertekker.ultraiptv.core.model.DeviceTier
import de.ostlertekker.ultraiptv.core.model.InfoEntry
import de.ostlertekker.ultraiptv.core.player.DisplayModeInfo
import javax.inject.Inject
import javax.inject.Singleton

data class DecoderInfo(val name: String, val mime: String, val hardware: Boolean, val hardwareAccuracy: Accuracy, val maxHeight: Int, val supports4k120: Boolean)

data class AudioCaps(val ac3: Boolean, val eac3: Boolean, val eac3Joc: Boolean, val dts: Boolean, val trueHd: Boolean, val maxChannels: Int)

@Singleton
class DeviceCapabilityManager @Inject constructor(@ApplicationContext private val context: Context) {

    val manufacturer: String = Build.MANUFACTURER ?: "?"
    val model: String = Build.MODEL ?: "?"
    val isFireTv: Boolean = context.packageManager.hasSystemFeature("amazon.hardware.fire_tv")
    val isAndroidTv: Boolean = context.packageManager.hasSystemFeature("android.software.leanback")

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    val totalMemBytes: Long by lazy {
        val mi = ActivityManager.MemoryInfo(); activityManager.getMemoryInfo(mi); mi.totalMem
    }
    val isLowRam: Boolean get() = activityManager.isLowRamDevice

    val decoders: List<DecoderInfo> by lazy { queryDecoders() }

    private fun display(): Display? =
        (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)

    fun displayModes(): List<DisplayModeInfo> =
        display()?.supportedModes?.map { DisplayModeInfo(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) } ?: emptyList()

    fun currentDisplayMode(): DisplayModeInfo? =
        display()?.mode?.let { DisplayModeInfo(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }

    @Suppress("DEPRECATION")
    fun hdrTypes(): List<String> {
        val caps = display()?.hdrCapabilities ?: return emptyList()
        return caps.supportedHdrTypes.map {
            when (it) {
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
                Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                4 -> "HDR10+"
                else -> "Typ $it"
            }
        }
    }

    @Suppress("DEPRECATION")
    val audioCaps: AudioCaps by lazy {
        val caps = AudioCapabilities.getCapabilities(context)
        AudioCaps(
            ac3 = caps.supportsEncoding(C.ENCODING_AC3),
            eac3 = caps.supportsEncoding(C.ENCODING_E_AC3),
            eac3Joc = caps.supportsEncoding(C.ENCODING_E_AC3_JOC),
            dts = caps.supportsEncoding(C.ENCODING_DTS),
            trueHd = caps.supportsEncoding(C.ENCODING_DOLBY_TRUEHD),
            maxChannels = caps.maxChannelCount,
        )
    }

    private fun queryDecoders(): List<DecoderInfo> {
        val mimes = listOf("video/avc", "video/hevc", "video/av01", "video/x-vnd.on2.vp9", "video/dolby-vision", "video/mp4v-es")
        val infos = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList() }.getOrDefault(emptyList())
        val out = ArrayList<DecoderInfo>()
        for (info in infos) {
            if (info.isEncoder) continue
            for (type in info.supportedTypes) {
                val mime = mimes.firstOrNull { it.equals(type, true) } ?: continue
                val caps = runCatching { info.getCapabilitiesForType(type).videoCapabilities }.getOrNull()
                val maxH = if (caps == null) 0 else listOf(2160, 1440, 1080, 720, 576, 480).firstOrNull { h ->
                    runCatching { caps.isSizeSupported(h * 16 / 9, h) }.getOrDefault(false)
                } ?: 0
                val r120 = caps != null && runCatching { caps.areSizeAndRateSupported(3840, 2160, 120.0) }.getOrDefault(false)
                val (hw, acc) = hardwareOf(info)
                out += DecoderInfo(info.name, mime, hw, acc, maxH, r120)
            }
        }
        return out
    }

    private fun hardwareOf(info: MediaCodecInfo): Pair<Boolean, Accuracy> {
        if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated to Accuracy.MEASURED
        val n = info.name.lowercase()
        val sw = n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw.") || n.endsWith(".sw")
        return !sw to Accuracy.ESTIMATED
    }

    /** Höchste Auflösung, die ein Hardware-Decoder für diesen MIME-Typ meldet (0 = keiner). */
    fun maxHardwareHeight(mime: String): Int =
        decoders.filter { it.mime == mime && it.hardware }.maxOfOrNull { it.maxHeight } ?: 0

    fun maxDecoderHeight(): Int = maxOf(maxHardwareHeight("video/hevc"), maxHardwareHeight("video/avc"), maxHardwareHeight("video/av01"))

    val tier: DeviceTier by lazy {
        val gb = totalMemBytes / (1024.0 * 1024 * 1024)
        val uhd = maxDecoderHeight() >= 2160
        when {
            gb >= 2.8 && uhd && !isLowRam -> DeviceTier.HIGH_PERFORMANCE
            gb >= 1.7 && uhd && !isLowRam -> DeviceTier.PREMIUM
            gb >= 1.3 && !isLowRam -> DeviceTier.STANDARD
            else -> DeviceTier.ENTRY
        }
    }

    val recommendedAnimation: AnimationProfile
        get() = when (tier) {
            DeviceTier.ENTRY -> AnimationProfile.ECONOMY
            DeviceTier.STANDARD, DeviceTier.PREMIUM -> AnimationProfile.BALANCED
            DeviceTier.HIGH_PERFORMANCE -> AnimationProfile.ULTRA
        }

    fun thermalStatus(): Pair<String, Accuracy> {
        if (Build.VERSION.SDK_INT < 29) return "Vom System nicht gemeldet (Android < 10)" to Accuracy.UNAVAILABLE
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val label = when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "Normal"
            PowerManager.THERMAL_STATUS_LIGHT -> "Leicht erhöht"
            PowerManager.THERMAL_STATUS_MODERATE -> "Mäßig (Drosselung möglich)"
            PowerManager.THERMAL_STATUS_SEVERE -> "Hoch (Drosselung aktiv)"
            else -> "Kritisch"
        }
        return label to Accuracy.MEASURED
    }

    fun fireOsLabel(): Pair<String, Accuracy> {
        if (!isFireTv) return "Kein Fire-TV-Gerät" to Accuracy.MEASURED
        val v = when (Build.VERSION.SDK_INT) {
            25 -> "Fire OS 6"
            28 -> "Fire OS 7"
            30 -> "Fire OS 8"
            else -> "Fire OS (API ${Build.VERSION.SDK_INT})"
        }
        return v to Accuracy.ESTIMATED
    }

    /** Detaillierter Gerätebericht für die Diagnose. Nur echte Werte – Unbekanntes wird so gekennzeichnet. */
    fun report(): List<InfoEntry> {
        val e = ArrayList<InfoEntry>()
        e += InfoEntry("Hersteller", manufacturer, Accuracy.MEASURED)
        e += InfoEntry("Modell", model, Accuracy.MEASURED)
        e += InfoEntry("Gerät / Board", "${Build.DEVICE} / ${Build.BOARD}", Accuracy.MEASURED)
        e += InfoEntry("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", Accuracy.MEASURED)
        val (fos, fosAcc) = fireOsLabel(); e += InfoEntry("Fire OS", fos, fosAcc)
        e += InfoEntry("Android-TV-Oberfläche", if (isAndroidTv) "Ja" else "Nein", Accuracy.MEASURED)
        e += InfoEntry("Arbeitsspeicher gesamt", "%.2f GB".format(totalMemBytes / (1024.0 * 1024 * 1024)), Accuracy.MEASURED)
        e += InfoEntry("Low-RAM-Gerät", if (isLowRam) "Ja" else "Nein", Accuracy.MEASURED)
        e += InfoEntry("App-Speicherlimit", "${activityManager.memoryClass} MB", Accuracy.MEASURED)
        e += InfoEntry("Leistungsprofil", tier.label, Accuracy.ESTIMATED)
        val cur = currentDisplayMode()
        e += InfoEntry("Aktueller Anzeigemodus", cur?.let { "${it.width}×${it.height} @ ${"%.2f".format(it.refreshRate)} Hz" } ?: "unbekannt",
            if (cur != null) Accuracy.MEASURED else Accuracy.UNAVAILABLE)
        val modes = displayModes()
        e += InfoEntry("Verfügbare Anzeigemodi", if (modes.isEmpty()) "keine gemeldet" else modes.joinToString(", ") { "${it.height}p${"%.0f".format(it.refreshRate)}" },
            if (modes.isEmpty()) Accuracy.UNAVAILABLE else Accuracy.MEASURED)
        val maxHz = modes.maxOfOrNull { it.refreshRate } ?: 0f
        e += InfoEntry("120-Hz-Ausgabe", if (maxHz >= 119f) "Vom Display-Pfad gemeldet" else "Nicht verfügbar (max. ${"%.0f".format(maxHz)} Hz gemeldet)", Accuracy.MEASURED)
        val hdr = hdrTypes()
        e += InfoEntry("HDR (Display/HDMI)", if (hdr.isEmpty()) "Kein HDR gemeldet" else hdr.joinToString(", "), Accuracy.MEASURED)
        listOf("video/avc" to "H.264", "video/hevc" to "HEVC", "video/av01" to "AV1", "video/x-vnd.on2.vp9" to "VP9", "video/dolby-vision" to "Dolby Vision").forEach { (mime, label) ->
            val list = decoders.filter { it.mime == mime }
            val value = if (list.isEmpty()) "Kein Decoder" else list.joinToString("; ") { d ->
                "${d.name} (${if (d.hardware) "HW" else "SW"}, bis ${d.maxHeight}p${if (d.supports4k120) ", 4K120" else ""})"
            }
            val acc = when {
                list.isEmpty() -> Accuracy.MEASURED
                list.any { it.hardwareAccuracy == Accuracy.ESTIMATED } -> Accuracy.ESTIMATED
                else -> Accuracy.MEASURED
            }
            e += InfoEntry("Decoder $label", value, acc)
        }
        val a = audioCaps
        e += InfoEntry("Audio-Passthrough (HDMI-Kette)", buildList {
            if (a.ac3) add("Dolby Digital"); if (a.eac3) add("Dolby Digital Plus"); if (a.eac3Joc) add("DD+ Atmos (JOC)")
            if (a.dts) add("DTS"); if (a.trueHd) add("TrueHD")
        }.ifEmpty { listOf("Nur PCM") }.joinToString(", "), Accuracy.MEASURED)
        e += InfoEntry("Max. Audiokanäle", a.maxChannels.toString(), Accuracy.MEASURED)
        val (th, thAcc) = thermalStatus(); e += InfoEntry("Thermischer Zustand", th, thAcc)
        return e
    }
}
