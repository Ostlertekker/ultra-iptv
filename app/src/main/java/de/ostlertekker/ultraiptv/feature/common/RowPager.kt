package de.ostlertekker.ultraiptv.feature.common

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * „Endlose“ Reihen: jede Reihe startet mit [base] Einträgen; erreicht der Fokus das Ende, werden [STEP] weitere
 * aus der Datenbank nachgeladen (bis alle da sind). Schlüssel stabil → die Reihe behält ihre Position.
 */
class RowPager {
    private val limits = MutableStateFlow<Map<String, Int>>(emptyMap())

    @OptIn(ExperimentalCoroutinesApi::class)
    fun <T> paged(key: String, base: Int, source: (Int) -> Flow<List<T>>): Flow<List<T>> =
        limits.map { it[key] ?: base }.distinctUntilChanged().flatMapLatest(source)

    /** Nächste Einträge laden (aufgerufen, wenn der Fokus kurz vor dem Ende der Reihe ist). */
    fun more(key: String, current: Int) {
        limits.update { m -> val now = maxOf(m[key] ?: 0, current); m + (key to (now + STEP).coerceAtMost(MAX)) }
    }

    companion object {
        const val STEP = 10
        private const val MAX = 2_000
    }
}
