package de.ostlertekker.ultraiptv.core.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.util.Locale

/** Mikrofon-Symbol (Material „mic“). */
val MicIcon: ImageVector by lazy {
    ImageVector.Builder("Mic", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3z" +
            "M17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z"),
            fill = SolidColor(Color.White))
    }.build()
}

/**
 * Spracheingabe für Suchfelder – funktioniert auf möglichst vielen Geräten:
 * 1. In der App mit dem Spracherkenner des Geräts (Google TV / Android TV) – eigenes „Ich höre zu“-Fenster.
 * 2. Sonst über den Sprachdialog des Systems, falls vorhanden.
 * 3. Sonst Hinweis auf die Diktierfunktion der Bildschirmtastatur (z. B. Fire TV: Mikrofontaste halten).
 */
class VoiceSearchState internal constructor(internal val start: () -> Unit) {
    fun listen() = start()
}

@Composable
fun rememberVoiceSearch(onResultIn: (String) -> Unit): VoiceSearchState {
    val context = LocalContext.current
    val latest = androidx.compose.runtime.rememberUpdatedState(onResultIn)
    val onResult: (String) -> Unit = { latest.value(it) }
    var listening by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }
    var level by remember { mutableStateOf(0f) }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    val lang = remember { Locale.getDefault().toLanguageTag() }

    fun stop() { runCatching { recognizer?.cancel(); recognizer?.destroy() }; recognizer = null; listening = false; partial = ""; level = 0f }
    DisposableEffect(Unit) { onDispose { stop() } }

    val systemDialog = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK)
            r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(onResult)
    }

    fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PROMPT, "Was möchtest du suchen?")
    }

    fun startInApp() {
        stop()
        val sr = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull() ?: return fallback(context, systemDialog::launch, ::recognizerIntent)
        recognizer = sr; listening = true
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Nichts verstanden – bitte noch einmal"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mikrofon-Berechtigung fehlt"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Spracherkennung: keine Verbindung"
                    else -> null
                }
                stop()
                if (msg != null) Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                else fallback(context, systemDialog::launch, ::recognizerIntent)
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                stop()
                text?.takeIf { it.isNotBlank() }?.let(onResult)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { partial = it }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        runCatching { sr.startListening(recognizerIntent()) }.onFailure { stop(); fallback(context, systemDialog::launch, ::recognizerIntent) }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startInApp() else fallback(context, systemDialog::launch, ::recognizerIntent)
    }

    if (listening) Dialog(onDismissRequest = { stop() }) {
        val pulse by rememberInfiniteTransition(label = "mic").animateFloat(1f, 1.15f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "micP")
        Column(Modifier.dialogEnter().width(460.dp).background(Color(0xFF0E1013), RoundedCornerShape(20.dp)).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(84.dp).scale(pulse + level * 0.25f).background(Color.White.copy(alpha = 0.12f + level * 0.25f), CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(MicIcon, null, tint = Color.White, modifier = Modifier.size(40.dp))
            }
            Text(if (partial.isBlank()) "Ich höre zu …" else partial, color = Color.White, fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text("Sprich jetzt deinen Suchbegriff · Zurück bricht ab", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
        }
    }

    return remember {
        VoiceSearchState {
            val inApp = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
            when {
                inApp && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> startInApp()
                inApp -> permission.launch(Manifest.permission.RECORD_AUDIO)
                else -> fallback(context, systemDialog::launch, ::recognizerIntent)
            }
        }
    }
}

private fun fallback(context: Context, launch: (Intent) -> Unit, intent: () -> Intent) {
    val i = intent()
    if (i.resolveActivity(context.packageManager) != null && runCatching { launch(i) }.isSuccess) return
    Toast.makeText(context, "Kein Spracherkenner auf diesem Gerät. Tipp: Suchfeld mit OK öffnen und die Mikrofontaste der Fernbedienung gedrückt halten.",
        Toast.LENGTH_LONG).show()
}

/** Runder Mikrofon-Knopf neben einem Suchfeld. */
@Composable
fun VoiceSearchButton(voice: VoiceSearchState, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = CircleShape
    Box(modifier.size(46.dp)
        .tvFocusable(RoundedCornerShape(50), { voice.listen() }, onFocus = { focused = it })
        .outlineButton(focused, primary = true, enabled = true, shape = shape), contentAlignment = Alignment.Center) {
        Icon(MicIcon, contentDescription = "Spracheingabe", tint = outlineButtonText(focused, true), modifier = Modifier.size(22.dp))
    }
}
