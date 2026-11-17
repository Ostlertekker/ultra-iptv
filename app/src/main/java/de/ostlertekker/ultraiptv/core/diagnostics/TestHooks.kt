package de.ostlertekker.ultraiptv.core.diagnostics

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.ostlertekker.ultraiptv.core.data.ContentRepository
import de.ostlertekker.ultraiptv.core.data.SettingsRepository
import de.ostlertekker.ultraiptv.core.data.SyncRepository
import de.ostlertekker.ultraiptv.core.data.UserRepository
import de.ostlertekker.ultraiptv.core.security.CredentialStore

/** Zugriff auf App-Singletons für automatische Oberflächentests (Testdaten anlegen). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TestHooks {
    fun settings(): SettingsRepository
    fun credentials(): CredentialStore
    fun sync(): SyncRepository
    fun user(): UserRepository
    fun content(): ContentRepository
    fun jellyfin(): de.ostlertekker.ultraiptv.core.jellyfin.JellyfinRepository
    fun spotify(): de.ostlertekker.ultraiptv.core.spotify.SpotifyRepository
}
