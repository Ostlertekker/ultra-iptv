package de.ostlertekker.ultraiptv.core.update

import de.ostlertekker.ultraiptv.core.i18n.tr
import de.ostlertekker.ultraiptv.core.i18n.trf

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import de.ostlertekker.ultraiptv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val version: String) : UpdateState
    data class Available(val version: String, val versionCode: Int, val notes: String, val url: String, val sizeBytes: Long) : UpdateState
    data class Downloading(val version: String, val progress: Float) : UpdateState
    data class ReadyToInstall(val version: String, val file: File) : UpdateState
    data class Error(val message: String) : UpdateState
}

/**
 * App-Updater über GitHub-Releases. Die Versionsnummer eines Releases (v1.0.N) entspricht dem
 * versionCode N des Builds. Release- und Debug-Version laden jeweils ihre passende APK.
 */
@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val assetName = if (BuildConfig.DEBUG) "UltraIPTV-debug.apk" else "UltraIPTV.apk"
    val currentVersion: String = BuildConfig.VERSION_NAME

    suspend fun check(): UpdateState = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Checking
        val result = try {
            val req = Request.Builder().url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
                .header("Accept", "application/vnd.github+json").build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error(trf("GitHub antwortet mit HTTP {0}", resp.code))
                val o = Json.parseToJsonElement(resp.body?.string() ?: "").let { it as JsonObject }
                val tag = (o["tag_name"] as? JsonPrimitive)?.contentOrNull ?: error(tr("Kein Release gefunden"))
                val code = tag.substringAfterLast('.').filter { it.isDigit() }.toIntOrNull() ?: 0
                val notes = (o["body"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val asset = (o["assets"] as? JsonArray)?.mapNotNull { it as? JsonObject }
                    ?.firstOrNull { (it["name"] as? JsonPrimitive)?.contentOrNull == assetName }
                    ?: error(trf("Release {0} enthält keine {1}", tag, assetName))
                val url = (asset["browser_download_url"] as JsonPrimitive).content
                val size = (asset["size"] as? JsonPrimitive)?.longOrNull ?: 0L
                if (code > BuildConfig.VERSION_CODE) UpdateState.Available(tag.removePrefix("v"), code, notes, url, size)
                else UpdateState.UpToDate(BuildConfig.VERSION_NAME)
            }
        } catch (e: Exception) {
            UpdateState.Error(e.message ?: tr("Update-Prüfung fehlgeschlagen"))
        }
        _state.value = result
        result
    }

    suspend fun download(update: UpdateState.Available) = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Downloading(update.version, 0f)
        try {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "UltraIPTV-${update.version}.apk")
            http.newCall(Request.Builder().url(update.url).build()).execute().use { resp ->
                if (!resp.isSuccessful) error(trf("Download: HTTP {0}", resp.code))
                val body = resp.body ?: error(tr("Download: leere Antwort"))
                val total = body.contentLength().takeIf { it > 0 } ?: update.sizeBytes
                body.byteStream().use { input ->
                    target.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        var lastReport = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0 && read - lastReport > 256 * 1024) {
                                lastReport = read
                                _state.value = UpdateState.Downloading(update.version, (read.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
            _state.value = UpdateState.ReadyToInstall(update.version, target)
        } catch (e: Exception) {
            _state.value = UpdateState.Error(trf("Download fehlgeschlagen: {0}", e.message))
        }
    }

    /** Darf die App Pakete installieren? (Android 8+, auf Fire OS 6 global geregelt) */
    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** Öffnet die System-Einstellung "Unbekannte Apps installieren" für ULTRA IPTV. */
    fun openInstallPermissionSettings(): Boolean = if (Build.VERSION.SDK_INT < 26) false else try {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    fun install(file: File): Boolean = try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        true
    } catch (e: Exception) {
        _state.value = UpdateState.Error(trf("Installation konnte nicht gestartet werden: {0}", e.message))
        false
    }
}
