package de.ostlertekker.ultraiptv.core.player

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import de.ostlertekker.ultraiptv.core.model.BufferProfile
import javax.inject.Inject

/**
 * Hintergrund-Audio: hält die laufende Wiedergabe als Media-Session im Vordergrunddienst,
 * wenn der Player verlassen wird (Einstellung "Hintergrund-Audio"). Fernbedienungs-Medientasten
 * steuern die Session auch dann.
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {
    @Inject lateinit var engine: PlayerEngine
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = engine.player ?: engine.obtain(BufferProfile.STANDARD)
        session = MediaSession.Builder(this, player).setId("ultra_iptv").build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        session?.release()   // Player gehört der PlayerEngine und wird hier nicht freigegeben
        session = null
        super.onDestroy()
    }
}
