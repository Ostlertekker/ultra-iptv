package de.ostlertekker.ultraiptv.core.notify

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import de.ostlertekker.ultraiptv.core.data.SettingsRepository
import de.ostlertekker.ultraiptv.core.data.SyncRepository
import de.ostlertekker.ultraiptv.core.data.UserRepository
import de.ostlertekker.ultraiptv.core.model.ContentKind
import de.ostlertekker.ultraiptv.core.player.PlayerEngine
import de.ostlertekker.ultraiptv.core.security.CredentialStore
import de.ostlertekker.ultraiptv.openPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Was nach einem Autostart passiert (einmal pro App-Prozess): auf Netzwerk warten, letzten Sender abspielen,
 * Playlist aktualisieren. Jeder Schritt ist abgesichert – fehlt Netz, Quelle oder Sender, wird das protokolliert und
 * kurz angezeigt, die App läuft normal weiter.
 */
@Singleton
class AutoStartFlow @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val settings: SettingsRepository,
    private val user: UserRepository,
    private val sync: SyncRepository,
    private val creds: CredentialStore,
    private val engine: PlayerEngine,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var done = false

    fun run(activity: Activity) {
        if (done) return
        done = true
        val wantChannel = AutoStart.get(ctx, AutoStart.K_LAST_CHANNEL)
        val wantPlaylist = AutoStart.get(ctx, AutoStart.K_PLAYLIST)
        if (!wantChannel && !wantPlaylist) return
        val act = WeakReference(activity)
        scope.launch {
            runCatching {
                if (runCatching { creds.load() }.getOrNull() == null) { AutoStart.log(ctx, "Keine Quelle eingerichtet – kein Sender/keine Playlist"); return@launch }
                if (!waitForNetwork(45_000)) {
                    AutoStart.log(ctx, "Kein Netzwerk nach 45 s – Sender wird nicht automatisch gestartet")
                    toast("Kein Netzwerk – letzter Sender wird nicht automatisch gestartet"); return@launch
                }
                if (wantChannel) playLastChannel(act)
                if (wantPlaylist) {
                    if (wantChannel) delay(20_000) // erst Wiedergabe stabil, dann im Hintergrund aktualisieren
                    val ok = withContext(Dispatchers.IO) { runCatching { sync.syncAll(includeEpg = false) }.getOrDefault(false) }
                    AutoStart.log(ctx, if (ok) "✓ Playlist aktualisiert" else "Playlist nicht erreichbar – gespeicherte Liste wird verwendet")
                }
            }.onFailure { AutoStart.log(ctx, "Autostart-Ablauf: Fehler ${it.javaClass.simpleName}") }
        }
    }

    private suspend fun playLastChannel(act: WeakReference<Activity>) {
        if (settings.current().startAction == 2) { AutoStart.log(ctx, "Letzter Sender: übernimmt „Beim App-Start“ (bereits eingestellt)"); return }
        val id = user.recentChannels.first().firstOrNull()?.itemId
        if (id == null) { AutoStart.log(ctx, "Noch kein zuletzt gesehener Sender"); return }
        delay(5_500) // Intro ausspielen lassen
        val a = act.get()
        when {
            a == null || a.isFinishing || a.isDestroyed -> AutoStart.log(ctx, "Letzter Sender: App wurde inzwischen geschlossen")
            engine.playing.value -> AutoStart.log(ctx, "Letzter Sender: Wiedergabe läuft bereits")
            else -> { a.openPlayer(ContentKind.LIVE, id); AutoStart.log(ctx, "✓ Letzter Sender gestartet") }
        }
    }

    private fun online(): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }

    private suspend fun waitForNetwork(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { while (!online()) delay(1_000); true } ?: false

    private fun toast(msg: String) = android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
}
