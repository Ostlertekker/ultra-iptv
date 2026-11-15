package de.ostlertekker.ultraiptv.core.search

import androidx.sqlite.db.SimpleSQLiteQuery
import de.ostlertekker.ultraiptv.core.data.UserRepository
import de.ostlertekker.ultraiptv.core.database.ChannelEntity
import de.ostlertekker.ultraiptv.core.database.ContentDao
import de.ostlertekker.ultraiptv.core.database.EpisodeEntity
import de.ostlertekker.ultraiptv.core.database.MovieEntity
import de.ostlertekker.ultraiptv.core.database.SeriesEntity
import de.ostlertekker.ultraiptv.core.model.Countries
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class SmartResults(
    val parsed: ParsedQuery? = null,
    val channels: List<ChannelEntity> = emptyList(),
    val movies: List<MovieEntity> = emptyList(),
    val series: List<SeriesEntity> = emptyList(),
    val episodes: List<EpisodeEntity> = emptyList(),
    /** Verständliche Beschreibung, wie die Anfrage interpretiert wurde. */
    val understood: String = "",
) {
    val isEmpty get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty() && episodes.isEmpty()
}

data class Suggestion(val label: String, val query: String)

@Singleton
class SearchRepository @Inject constructor(
    private val dao: ContentDao,
    private val user: UserRepository,
) {
    private suspend fun adult() = user.filter.map { it.showAdult }.first()

    /** Universelle Suche über Live-TV, Filme, Serien und Episoden mit Verständnis natürlicher Anfragen. */
    suspend fun search(query: String): SmartResults {
        val q = SmartSearch.parse(query)
        if (q.terms.isEmpty() && !q.hasFilters) return SmartResults(q)
        val showAdult = adult()
        val wants = q.kinds.ifEmpty { SearchKind.entries.toSet() }
        val likeTerms = candidatesTerms(q.terms)

        val movies = if (SearchKind.MOVIE in wants) rank(dao.rawMovies(movieSql(q, likeTerms, showAdult)), q) { it.name } else emptyList()
        val series = if (SearchKind.SERIES in wants) rank(dao.rawSeries(seriesSql(q, likeTerms, showAdult)), q) { it.name } else emptyList()
        val channels = if (SearchKind.LIVE in wants && q.actor == null && q.yearFrom == null)
            rank(dao.rawChannels(channelSql(q, likeTerms, showAdult)), q) { it.name } else emptyList()
        val episodes = if ((SearchKind.EPISODE in wants || q.kinds.isEmpty()) && q.terms.isNotEmpty() && !q.hasFilters)
            dao.searchEpisodes(q.terms.first(), 80).filter { SmartSearch.score(it.title, q.terms) > 0 }
                .sortedByDescending { SmartSearch.score(it.title, q.terms) }.take(30)
        else emptyList()
        return SmartResults(q, channels.take(40), movies.take(80), series.take(50), episodes, describe(q))
    }

    private fun <T> rank(list: List<T>, q: ParsedQuery, title: (T) -> String): List<T> {
        if (q.terms.isEmpty()) return list
        return list.map { it to SmartSearch.score(title(it), q.terms) }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
    }

    /** Für Tippfehler-Toleranz auch nach Wortanfängen suchen (z. B. "brea" für "breakng"). */
    private fun candidatesTerms(terms: List<String>): List<String> =
        terms.take(3).flatMap { t -> if (t.length >= 5) listOf(t, t.take(4)) else listOf(t) }.distinct()

    private fun likeClause(columns: List<String>, terms: List<String>, args: MutableList<Any>): String {
        if (terms.isEmpty()) return "1"
        return terms.joinToString(" OR ", "(", ")") { t ->
            columns.joinToString(" OR ") { c -> args += "%$t%"; "$c LIKE ?" }
        }
    }

    private fun genreClause(q: ParsedQuery, columns: List<String>, args: MutableList<Any>): String {
        if (q.genres.isEmpty()) return "1"
        return q.genres.values.joinToString(" AND ", "(", ")") { syns ->
            syns.joinToString(" OR ", "(", ")") { s -> columns.joinToString(" OR ") { c -> args += "%$s%"; "$c LIKE ?" } }
        }
    }

    private fun langClause(q: ParsedQuery, langColumn: String, textColumns: List<String>, args: MutableList<Any>): String {
        val lang = q.language ?: return "1"
        val tags = Countries.all.filter { it.language == lang }.map { it.code }
        val sb = StringBuilder("($langColumn = ?")
        args += lang
        tags.forEach { code -> textColumns.forEach { c -> sb.append(" OR $c LIKE ?"); args += "$code:%"; sb.append(" OR $c LIKE ?"); args += "%|$code|%" } }
        return sb.append(")").toString()
    }

    private fun movieSql(q: ParsedQuery, terms: List<String>, adult: Boolean): SimpleSQLiteQuery {
        val args = ArrayList<Any>()
        val where = buildList {
            add(likeClause(listOf("name"), terms, args))
            add(genreClause(q, listOf("genre", "category"), args))
            add(langClause(q, "language", listOf("category", "name"), args))
            q.actor?.let { a -> add("(actors LIKE ? OR director LIKE ?)"); args += "%$a%"; args += "%$a%" }
            if (q.yearFrom != null && q.yearTo != null) { add("(CAST(year AS INTEGER) BETWEEN ? AND ?)"); args += q.yearFrom; args += q.yearTo }
            if (!adult) add("adult = 0")
        }.joinToString(" AND ")
        val order = if (q.terms.isEmpty()) "rating DESC, addedAt DESC" else "addedAt DESC"
        return SimpleSQLiteQuery("SELECT * FROM movies WHERE $where ORDER BY $order LIMIT 400", args.toTypedArray())
    }

    private fun seriesSql(q: ParsedQuery, terms: List<String>, adult: Boolean): SimpleSQLiteQuery {
        val args = ArrayList<Any>()
        val where = buildList {
            add(likeClause(listOf("name"), terms, args))
            add(genreClause(q, listOf("genre", "category"), args))
            add(langClause(q, "language", listOf("category", "name"), args))
            q.actor?.let { a -> add("(actors LIKE ?)"); args += "%$a%" }
            if (q.yearFrom != null && q.yearTo != null) { add("(CAST(year AS INTEGER) BETWEEN ? AND ?)"); args += q.yearFrom; args += q.yearTo }
            if (!adult) add("adult = 0")
        }.joinToString(" AND ")
        val order = if (q.terms.isEmpty()) "rating DESC, addedAt DESC" else "addedAt DESC"
        return SimpleSQLiteQuery("SELECT * FROM series WHERE $where ORDER BY $order LIMIT 300", args.toTypedArray())
    }

    private fun channelSql(q: ParsedQuery, terms: List<String>, adult: Boolean): SimpleSQLiteQuery {
        val args = ArrayList<Any>()
        val where = buildList {
            add(likeClause(listOf("name"), terms, args))
            add(genreClause(q, listOf("category", "name"), args))
            val lang = q.language
            if (lang != null) {
                val codes = Countries.all.filter { it.language == lang }.map { it.code }
                if (codes.isNotEmpty()) add(codes.joinToString(" OR ", "(", ")") { args += it; "country = ?" })
            }
            if (!adult) add("adult = 0")
        }.joinToString(" AND ")
        return SimpleSQLiteQuery("SELECT * FROM channels WHERE $where ORDER BY number, name LIMIT 300", args.toTypedArray())
    }

    private fun describe(q: ParsedQuery): String {
        val parts = ArrayList<String>()
        if (q.kinds.isNotEmpty()) parts += q.kinds.joinToString("/") { when (it) { SearchKind.LIVE -> "Sender"; SearchKind.MOVIE -> "Filme"; SearchKind.SERIES -> "Serien"; SearchKind.EPISODE -> "Episoden" } }
        if (q.genres.isNotEmpty()) parts += "Genre: " + q.genres.keys.joinToString(", ")
        q.language?.let { parts += "Sprache: " + (Countries.languageNames[it] ?: it) }
        if (q.yearFrom != null) parts += if (q.yearFrom == q.yearTo) "Jahr ${q.yearFrom}" else "${q.yearFrom}–${q.yearTo}"
        q.actor?.let { parts += "mit „$it“" }
        if (q.terms.isNotEmpty()) parts += "„${q.terms.joinToString(" ")}“"
        return if (parts.isEmpty()) "" else "Verstanden: " + parts.joinToString(" · ")
    }

    /** Vorschläge während der Eingabe: passende Titel, Genres, Sprachen und Verlauf. */
    suspend fun suggestions(input: String, history: List<String>): List<Suggestion> {
        val text = input.trim()
        val out = LinkedHashMap<String, Suggestion>()
        history.filter { text.isBlank() || it.startsWith(text, true) }.take(4).forEach { out[it.lowercase()] = Suggestion("⟲ $it", it) }
        if (text.length >= 2) {
            val norm = SmartSearch.normalize(text)
            SmartSearch.genreSynonyms.keys.filter { SmartSearch.normalize(it).startsWith(norm) }.take(2).forEach {
                out["g$it"] = Suggestion("Genre: $it", "$it filme")
            }
            Countries.languageNames.entries.filter { SmartSearch.normalize(it.value).startsWith(norm) }.take(2).forEach { (_, name) ->
                out["l$name"] = Suggestion("$name Sender", "$name sender")
            }
            val adult = adult()
            val titles = dao.searchMovies(text, adult, 6).map { it.name } + dao.searchSeries(text, adult, 6).map { it.name } +
                dao.searchChannels(text, adult, 6).map { it.name }
            titles.sortedByDescending { SmartSearch.score(it, SmartSearch.normalize(text).split(' ')) }.take(6)
                .forEach { out[it.lowercase()] = Suggestion(it, it) }
        } else if (text.isBlank()) {
            listOf("Neue Actionfilme" to "action filme", "Deutsche Sender" to "deutsche sender", "Komödien" to "komödie filme",
                "Dokus" to "doku", "Kinder & Familie" to "kinder", "Sport live" to "sport sender").forEach { (l, qq) -> out[l] = Suggestion(l, qq) }
        }
        return out.values.take(10)
    }

    /** Ähnliche Filme: gleiches Genre, gleiche Darsteller oder gleiche Kategorie. */
    suspend fun similarMovies(m: MovieEntity, limit: Int = 20): List<MovieEntity> {
        val args = ArrayList<Any>()
        val conds = ArrayList<String>()
        m.genre?.split(',', '/', '|')?.map { it.trim() }?.filter { it.length > 2 }?.take(3)?.forEach { conds += "genre LIKE ?"; args += "%$it%" }
        m.actors?.split(',')?.map { it.trim() }?.filter { it.length > 3 }?.take(3)?.forEach { conds += "actors LIKE ?"; args += "%$it%" }
        conds += "category = ?"; args += m.category
        val adult = if (adult()) "" else " AND adult = 0"
        args += m.id
        val list = dao.rawMovies(SimpleSQLiteQuery("SELECT * FROM movies WHERE (${conds.joinToString(" OR ")})$adult AND id != ? LIMIT 200", args.toTypedArray()))
        return list.sortedByDescending { SmartSearch.similarity(m.genre, it.genre, m.actors, it.actors, m.category, it.category) + ((it.rating ?: 0.0) / 50.0).toFloat() }
            .take(limit)
    }

    suspend fun similarSeries(s: SeriesEntity, limit: Int = 20): List<SeriesEntity> {
        val args = ArrayList<Any>()
        val conds = ArrayList<String>()
        s.genre?.split(',', '/', '|')?.map { it.trim() }?.filter { it.length > 2 }?.take(3)?.forEach { conds += "genre LIKE ?"; args += "%$it%" }
        s.actors?.split(',')?.map { it.trim() }?.filter { it.length > 3 }?.take(3)?.forEach { conds += "actors LIKE ?"; args += "%$it%" }
        conds += "category = ?"; args += s.category
        val adult = if (adult()) "" else " AND adult = 0"
        args += s.id
        val list = dao.rawSeries(SimpleSQLiteQuery("SELECT * FROM series WHERE (${conds.joinToString(" OR ")})$adult AND id != ? LIMIT 200", args.toTypedArray()))
        return list.sortedByDescending { SmartSearch.similarity(s.genre, it.genre, s.actors, it.actors, s.category, it.category) + ((it.rating ?: 0.0) / 50.0).toFloat() }
            .take(limit)
    }
}
