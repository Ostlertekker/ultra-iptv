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

    override fun onCreate() {
        super.onCreate()
        credentialStore.init()
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
            .allowRgb565(low)
            .crossfade(!low)
            .respectCacheHeaders(false)
            .build()
    }
}
