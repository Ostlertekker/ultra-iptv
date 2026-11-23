package de.ostlertekker.ultraiptv

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import dagger.hilt.android.HiltAndroidApp
import de.ostlertekker.ultraiptv.core.security.CredentialStore
import javax.inject.Inject

@HiltAndroidApp
class UltraApp : Application(), ImageLoaderFactory {

    @Inject lateinit var credentialStore: CredentialStore
    @Inject lateinit var http: okhttp3.OkHttpClient

    override fun onCreate() {
        // Sprache vor allem anderen festlegen (Hilt baut in super.onCreate() schon Objekte mit Texten)
        de.ostlertekker.ultraiptv.core.i18n.I18n.init(this)
        super.onCreate()
        // Neustart-Hilfsprozess (Sprachwechsel): nichts weiter starten
        if (de.ostlertekker.ultraiptv.core.i18n.RestartActivity.isRestartProcess()) return
        de.ostlertekker.ultraiptv.core.diagnostics.StabilityGuard.install(this)
        credentialStore.init()
        // System-Updates: Prüfung beim App-Start (verzögert, damit der Start nicht leidet) und regelmäßig im Hintergrund
        runCatching {
            val m = dagger.hilt.android.EntryPointAccessors.fromApplication(this, de.ostlertekker.ultraiptv.core.sysupdate.SystemUpdateEntryPoint::class.java).manager()
            m.schedule()
            m.runAutomaticAsync("App-Start", delayMs = 25_000)
        }
    }

    /** Bild-Cache passend zum Gerät: auf schwachen Sticks kleiner und mit RGB565. */
    override fun newImageLoader(): ImageLoader {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val low = am.isLowRamDevice || am.memoryClass < 192
        return ImageLoader.Builder(this)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(if (low) 0.12 else 0.22).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("images"))
                    .maxSizeBytes(if (low) 64L * 1024 * 1024 else 200L * 1024 * 1024)
                    .build()
            }
            // Posterbilder ohne Transparenz brauchen nur halb so viel Speicher (Logos mit Alpha bleiben ARGB)
            .allowRgb565(true)
            // Nicht 64 riesige Poster gleichzeitig decodieren – das sprengt auf Fire-TV-Sticks den Speicher
            .fetcherDispatcher(kotlinx.coroutines.Dispatchers.IO.limitedParallelism(if (low) 4 else 6))
            .decoderDispatcher(kotlinx.coroutines.Dispatchers.Default.limitedParallelism(if (low) 1 else 2))
            // Kein Überblenden: jedes Bild wäre sonst 120 ms lang zusätzliche Zeichenarbeit beim Scrollen
            .crossfade(false)
            // Gemeinsamer Verbindungs-Pool mit der übrigen App statt eines zweiten OkHttp-Clients
            .okHttpClient { http }
            .respectCacheHeaders(false)
            .build()
    }
}
