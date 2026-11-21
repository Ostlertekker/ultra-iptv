package de.ostlertekker.ultraiptv.feature.web.adblock

/**
 * Blocking-Engine für Adblock-Plus-/EasyList-Filter (ohne Android-Abhängigkeiten, testbar).
 *
 * Netzwerkfilter: `||host^`, Anker `|`, Platzhalter `*`, Trenner `^`, Ausnahmen `@@`, Optionen
 * (third-party, Ressourcentypen, domain=, popup, document, match-case, important, elemhide, generichide).
 * Kosmetische Filter: `##selector` (allgemein), `domain##selector`, Ausnahmen `#@#`.
 * Nicht unterstützt (übersprungen): reguläre Ausdrücke `/…/`, erweitertes CSS (`#?#`), Snippets (`#$#`, `##+js`), csp/redirect/rewrite.
 *
 * Schnell auch bei >100 000 Regeln: Host-Regeln in Hash-Tabellen (Abfrage über alle Elternbereiche des Hosts),
 * übrige Regeln über einen Token-Index; Muster werden erst bei Bedarf in reguläre Ausdrücke übersetzt.
 */
class FilterEngine private constructor(
    private val hostBlock: HashMap<String, MutableList<NetRule>>,
    private val hostAllow: HashMap<String, MutableList<NetRule>>,
    private val tokenBlock: HashMap<String, MutableList<NetRule>>,
    private val tokenAllow: HashMap<String, MutableList<NetRule>>,
    private val restBlock: List<NetRule>,
    private val restAllow: List<NetRule>,
    /** Allgemeine Element-Selektoren (in Gruppen zu je ~200 als fertiges CSS). */
    val genericCss: List<String>,
    private val specificHide: HashMap<String, MutableList<String>>,
    private val specificExcluded: HashMap<String, MutableList<String>>,
    private val hideExceptions: HashMap<String, MutableList<String>>,
    private val genericExceptionsAll: HashSet<String>,
    /** „a.de,~b.a.de##sel“: Bereiche, in denen der Selektor trotz Treffer nicht gilt. */
    private val selectorExcludes: HashMap<String, MutableSet<String>>,
    val ruleCount: Int,
) {
    enum class Type { DOCUMENT, SUBDOCUMENT, SCRIPT, STYLESHEET, IMAGE, MEDIA, FONT, XHR, PING, WEBSOCKET, OBJECT, POPUP, OTHER }

    class NetRule(
        val pattern: String,
        val hostAnchor: String?,        // „||host^“ ohne weitere Teile
        val leftAnchor: Boolean,
        val domainAnchor: Boolean,
        val rightAnchor: Boolean,
        val types: Set<Type>?,          // null = alle (außer DOCUMENT/POPUP)
        val notTypes: Set<Type>,
        val thirdParty: Boolean?,       // null = egal
        val includeDomains: Set<String>?,
        val excludeDomains: Set<String>,
        val matchCase: Boolean,
        val important: Boolean,
        val pageFlags: Int,             // nur Ausnahmen: DOC/ELEMHIDE/GENERICHIDE
    ) {
        private var compiled: Regex? = null
        private var failed = false

        fun matchesUrl(url: String, urlLower: String): Boolean {
            if (hostAnchor != null) return true // bereits über die Host-Tabelle gefunden
            if (failed) return false
            val r = compiled ?: runCatching { Regex(toRegex(), if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)) }
                .getOrElse { failed = true; return false }.also { compiled = it }
            return r.containsMatchIn(if (matchCase) url else urlLower)
        }

        private fun toRegex(): String {
            val sb = StringBuilder()
            if (domainAnchor) sb.append("^[a-z][a-z0-9+.-]*://([^/?#]*\\.)?")
            else if (leftAnchor) sb.append('^')
            for (c in pattern) when (c) {
                '*' -> sb.append(".*")
                '^' -> sb.append("(?:[^\\w\\-.%]|$)")
                else -> if (c.isLetterOrDigit()) sb.append(c) else sb.append('\\').append(c)
            }
            if (rightAnchor) sb.append('$')
            return sb.toString()
        }

        fun appliesTo(type: Type, thirdPartyReq: Boolean, pageHost: String?): Boolean {
            when (type) {
                Type.DOCUMENT -> if (types == null || Type.DOCUMENT !in types) return false
                Type.POPUP -> if (types == null || Type.POPUP !in types) return false
                else -> {
                    if (types != null && type !in types) return false
                    if (type in notTypes) return false
                }
            }
            if (thirdParty != null && thirdParty != thirdPartyReq) return false
            if (includeDomains != null || excludeDomains.isNotEmpty()) {
                val h = pageHost ?: return includeDomains == null
                if (parents(h).any { it in excludeDomains }) return false
                if (includeDomains != null && parents(h).none { it in includeDomains }) return false
            }
            return true
        }
    }

    data class PageFlags(val allowAll: Boolean, val noElemHide: Boolean, val noGenericHide: Boolean)

    /** Wird die Anfrage blockiert? [pageHost] = Host der angezeigten Seite (für Drittanbieter und domain=). */
    fun shouldBlock(url: String, host: String, type: Type, pageHost: String?): Boolean {
        val third = pageHost != null && site(host) != site(pageHost)
        val lower = url.lowercase()
        val block = find(hostBlock, tokenBlock, restBlock, url, lower, host, type, third, pageHost) ?: return false
        if (block.important) return true
        return find(hostAllow, tokenAllow, restAllow, url, lower, host, type, third, pageHost) == null
    }

    /** Ausnahmen für die ganze Seite (@@…$document, $elemhide, $generichide). */
    fun pageFlags(pageUrl: String, pageHost: String): PageFlags {
        val lower = pageUrl.lowercase()
        var flags = 0
        fun check(r: NetRule) { if (r.pageFlags != 0 && r.matchesUrl(pageUrl, lower)) flags = flags or r.pageFlags }
        for (p in parents(pageHost)) hostAllow[p]?.forEach { check(it) }
        tokens(lower).forEach { t -> tokenAllow[t]?.forEach { check(it) } }
        restAllow.forEach { check(it) }
        return PageFlags(flags and DOC != 0, flags and ELEMHIDE != 0, flags and GENERICHIDE != 0)
    }

    /** Seitenspezifisches CSS (Werbeflächen dieser Seite ausblenden) – ohne die allgemeinen Selektoren. */
    fun specificCss(pageHost: String): String {
        val ps = parents(pageHost).toList()
        val exceptions = HashSet<String>()
        ps.forEach { p -> hideExceptions[p]?.let { exceptions.addAll(it) } }
        val sel = LinkedHashSet<String>()
        ps.forEach { p -> specificHide[p]?.forEach { s ->
            if (s !in exceptions && selectorExcludes[s]?.let { ex -> ps.any { it in ex } } != true) sel += s } }
        // Regeln „~domain##sel“ (überall außer …): nur wenn die Seite nicht ausgenommen ist
        specificExcluded.forEach { (s, excluded) -> if (ps.none { it in excluded } && s !in exceptions) sel += s }
        return cssOf(sel)
    }

    /** Allgemeine Selektoren, die auf dieser Seite per #@# ausgenommen sind (werden wieder sichtbar gemacht). */
    fun genericExceptionsFor(pageHost: String): List<String> {
        val out = ArrayList<String>()
        parents(pageHost).forEach { p -> hideExceptions[p]?.forEach { if (it in genericExceptionsAll) out += it } }
        return out
    }

    private fun find(
        hostMap: HashMap<String, MutableList<NetRule>>, tokenMap: HashMap<String, MutableList<NetRule>>, rest: List<NetRule>,
        url: String, lower: String, host: String, type: Type, third: Boolean, pageHost: String?,
    ): NetRule? {
        for (p in parents(host)) hostMap[p]?.forEach { r -> if (r.appliesTo(type, third, pageHost)) return r }
        for (t in tokens(lower)) tokenMap[t]?.forEach { r -> if (r.appliesTo(type, third, pageHost) && r.matchesUrl(url, lower)) return r }
        for (r in rest) if (r.appliesTo(type, third, pageHost) && r.matchesUrl(url, lower)) return r
        return null
    }

    companion object {
        const val DOC = 1; const val ELEMHIDE = 2; const val GENERICHIDE = 4

        private val TYPE_NAMES = mapOf(
            "script" to Type.SCRIPT, "image" to Type.IMAGE, "stylesheet" to Type.STYLESHEET, "css" to Type.STYLESHEET,
            "object" to Type.OBJECT, "xmlhttprequest" to Type.XHR, "xhr" to Type.XHR, "subdocument" to Type.SUBDOCUMENT,
            "frame" to Type.SUBDOCUMENT, "ping" to Type.PING, "beacon" to Type.PING, "media" to Type.MEDIA, "font" to Type.FONT,
            "websocket" to Type.WEBSOCKET, "other" to Type.OTHER, "popup" to Type.POPUP, "document" to Type.DOCUMENT, "doc" to Type.DOCUMENT,
        )
        private val IGNORED_OPTIONS = setOf("first-party", "1p", "3p", "all", "match-case", "important", "elemhide", "ehide",
            "generichide", "ghide", "genericblock", "collapse", "~collapse", "object-subrequest", "webrtc", "ping")
        /** Optionen, bei denen die Regel etwas anderes als Blockieren tut → Regel überspringen. */
        private val SKIP_OPTIONS = setOf("csp", "redirect", "redirect-rule", "rewrite", "removeparam", "replace", "header", "permissions",
            "cookie", "stealth", "urltransform", "badfilter", "inline-script", "inline-font", "empty", "mp4", "sitekey", "to", "denyallow", "method")
        private val SECOND_LEVEL = setOf("co", "com", "net", "org", "gov", "edu", "ac", "or", "ne", "go")

        /** Host und alle Elternbereiche: a.b.example.com → a.b.example.com, b.example.com, example.com, com */
        fun parents(host: String): Sequence<String> = generateSequence(host) { h -> h.indexOf('.').takeIf { it >= 0 }?.let { h.substring(it + 1) } }
            .takeWhile { it.isNotEmpty() }

        /** Registrierbare Domain (Näherung ohne vollständige Suffix-Liste: co.uk, com.au … werden berücksichtigt). */
        fun site(host: String): String {
            val parts = host.trimEnd('.').split('.')
            if (parts.size <= 2) return host
            val n = if (parts[parts.size - 2] in SECOND_LEVEL && parts.last().length == 2) 3 else 2
            return parts.takeLast(n).joinToString(".")
        }

        fun tokens(lower: String): Sequence<String> = TOKEN.findAll(lower).map { it.value }.filter { it.length >= 3 }.distinct()
        private val TOKEN = Regex("[a-z0-9%]+")

        fun cssOf(selectors: Collection<String>): String =
            if (selectors.isEmpty()) "" else selectors.chunked(150).joinToString("\n") { it.joinToString(",") + "{display:none!important}" }

        /** Selektoren, die ältere WebView-Versionen nicht verstehen oder die keine echten CSS-Selektoren sind. */
        private fun cssOk(sel: String): Boolean = sel.isNotBlank() && sel.length < 1000 && !sel.contains(":-abp-") && !sel.contains(":has(") &&
            !sel.contains(":has-text") && !sel.contains(":matches-") && !sel.contains(":xpath") && !sel.contains(":style(") &&
            !sel.contains(":remove(") && !sel.contains(":upward") && !sel.contains(":watch-attr") && !sel.contains("{") &&
            !sel.contains(":min-text-length") && !sel.contains(":others(") && !sel.contains(":-ext-") && !sel.startsWith("+js")

        /** Baut die Engine aus beliebig vielen Filterlisten (Zeilen). */
        fun build(lists: List<Sequence<String>>): FilterEngine {
            val hostBlock = HashMap<String, MutableList<NetRule>>(1 shl 17)
            val hostAllow = HashMap<String, MutableList<NetRule>>()
            val tokenBlock = HashMap<String, MutableList<NetRule>>(1 shl 15)
            val tokenAllow = HashMap<String, MutableList<NetRule>>()
            val restBlock = ArrayList<NetRule>(); val restAllow = ArrayList<NetRule>()
            val generic = LinkedHashSet<String>()
            val specific = HashMap<String, MutableList<String>>()
            val excluded = HashMap<String, MutableList<String>>()
            val exceptions = HashMap<String, MutableList<String>>()
            val genericExceptionsAll = HashSet<String>()
            val globalExceptions = HashSet<String>()
            val selExc = HashMap<String, MutableSet<String>>()
            var count = 0
            for (lines in lists) for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line[0] == '!' || line[0] == '[') continue
                // ---- kosmetische Filter
                val cosm = COSMETIC.find(line)
                if (cosm != null) {
                    val domains = line.substring(0, cosm.range.first)
                    val sep = cosm.value
                    val sel = line.substring(cosm.range.last + 1).trim()
                    if (sep != "##" && sep != "#@#") continue // #?#, #$#, #%# … nicht unterstützt
                    if (!cssOk(sel)) continue
                    val inc = domains.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() && !it.startsWith("~") }
                    val exc = domains.split(',').map { it.trim().lowercase() }.filter { it.startsWith("~") }.map { it.drop(1) }
                    if (sep == "#@#") {
                        if (inc.isEmpty()) globalExceptions += sel
                        inc.forEach { d -> exceptions.getOrPut(d) { ArrayList() }.add(sel) }
                        genericExceptionsAll += sel
                    } else when {
                        inc.isEmpty() && exc.isEmpty() -> generic += sel
                        inc.isEmpty() -> excluded.getOrPut(sel) { ArrayList() }.addAll(exc)
                        else -> {
                            inc.forEach { d -> specific.getOrPut(d) { ArrayList() }.add(sel) }
                            if (exc.isNotEmpty()) selExc.getOrPut(sel) { HashSet() }.addAll(exc)
                        }
                    }
                    count++
                    continue
                }
                // ---- Netzwerkfilter
                val rule = parseNet(line) ?: continue
                val allow = line.startsWith("@@")
                val host = rule.hostAnchor
                when {
                    host != null -> (if (allow) hostAllow else hostBlock).getOrPut(host) { ArrayList(1) }.add(rule)
                    else -> {
                        val tok = bestToken(rule)
                        if (tok != null) (if (allow) tokenAllow else tokenBlock).getOrPut(tok) { ArrayList(1) }.add(rule)
                        else (if (allow) restAllow else restBlock).add(rule)
                    }
                }
                count++
            }
            generic.removeAll(globalExceptions)
            return FilterEngine(hostBlock, hostAllow, tokenBlock, tokenAllow, restBlock, restAllow,
                generic.toList().chunked(200).map { it.joinToString(",") + "{display:none!important}" },
                specific, excluded, exceptions, genericExceptionsAll, selExc, count)
        }

        // Reihenfolge wichtig: „###id“ = Trenner „##“ + Selektor „#id“
        private val COSMETIC = Regex("#@#|#@?\\?#|#@?\\$#|#@?%#|##")

        fun parseNet(line0: String): NetRule? {
            var line = line0
            val allow = line.startsWith("@@")
            if (allow) line = line.substring(2)
            if (line.startsWith("/") && line.length > 2 && line.lastIndexOf('/') > 0 &&
                (line.endsWith("/") || line.contains("/$"))) return null // reguläre Ausdrücke: übersprungen
            var opts = ""
            val dollar = line.lastIndexOf('$')
            if (dollar >= 0 && dollar < line.length - 1 && !line.substring(dollar + 1).contains('/')) {
                opts = line.substring(dollar + 1); line = line.substring(0, dollar)
            }
            var types: MutableSet<Type>? = null
            val notTypes = HashSet<Type>()
            var third: Boolean? = null
            var include: MutableSet<String>? = null
            val exclude = HashSet<String>()
            var matchCase = false; var important = false; var flags = 0
            if (opts.isNotEmpty()) for (o0 in opts.split(',')) {
                val o = o0.trim().lowercase()
                if (o.isEmpty()) continue
                val name = o.substringBefore('=').removePrefix("~")
                if (name in SKIP_OPTIONS) return null
                when {
                    o == "third-party" || o == "3p" -> third = true
                    o == "~third-party" || o == "first-party" || o == "1p" -> third = false
                    o == "match-case" -> matchCase = true
                    o == "important" -> important = true
                    o == "document" || o == "doc" -> if (allow) flags = flags or DOC else (types ?: HashSet<Type>().also { types = it }).add(Type.DOCUMENT)
                    o == "elemhide" || o == "ehide" -> { if (!allow) return null; flags = flags or ELEMHIDE }
                    o == "generichide" || o == "ghide" -> { if (!allow) return null; flags = flags or GENERICHIDE }
                    o.startsWith("domain=") -> o.removePrefix("domain=").split('|').forEach { d ->
                        if (d.startsWith("~")) exclude += d.drop(1) else (include ?: HashSet<String>().also { include = it }).add(d)
                    }
                    o.startsWith("~") && TYPE_NAMES.containsKey(o.drop(1)) -> notTypes += TYPE_NAMES.getValue(o.drop(1))
                    TYPE_NAMES.containsKey(o) -> (types ?: HashSet<Type>().also { types = it }).add(TYPE_NAMES.getValue(o))
                    o == "all" -> (types ?: HashSet<Type>().also { types = it }).addAll(Type.entries)
                    o in IGNORED_OPTIONS -> Unit
                    else -> return null // unbekannte Option → Regel lieber nicht anwenden
                }
            }
            // reine Seiten-Ausnahmen ($elemhide/$generichide) ohne Ressourcentyp: nicht als Netzwerk-Ausnahme verwenden
            val pageOnly = allow && flags and (ELEMHIDE or GENERICHIDE) != 0 && types == null
            var domainAnchor = false; var left = false; var right = false
            if (line.startsWith("||")) { domainAnchor = true; line = line.substring(2) }
            else if (line.startsWith("|")) { left = true; line = line.substring(1) }
            if (line.endsWith("|")) { right = true; line = line.dropLast(1) }
            if (line.isEmpty() || line == "*") { if (!allow || flags == 0) return null }
            val pattern = if (matchCase) line else line.lowercase()
            // Schnellweg: „||host^“ bzw. „||host“
            val hostOnly = domainAnchor && !right && HOST_ONLY.matches(pattern)
            val host = if (hostOnly) pattern.trimEnd('^') else null
            return NetRule(
                pattern = pattern, hostAnchor = host, leftAnchor = left, domainAnchor = domainAnchor, rightAnchor = right,
                types = if (pageOnly) setOf(Type.DOCUMENT) else types, notTypes = notTypes, thirdParty = third,
                includeDomains = include, excludeDomains = exclude, matchCase = matchCase, important = important, pageFlags = flags,
            )
        }

        private val HOST_ONLY = Regex("[a-z0-9.-]+\\.[a-z0-9-]+\\^?")

        /** Seltenster/längster zusammenhängender Teil des Musters, der in jeder passenden URL vorkommen muss. */
        private fun bestToken(r: NetRule): String? {
            val p = r.pattern.lowercase()
            var best: String? = null
            for (m in TOKEN.findAll(p)) {
                val s = m.range.first; val e = m.range.last + 1
                if (s > 0 && p[s - 1] == '*') continue
                if (e < p.length && p[e] == '*') continue
                // am Musteranfang ohne Anker kann der Teil in der URL länger sein → kein sicherer Token
                if (s == 0 && !r.leftAnchor && !r.domainAnchor) continue
                if (e == p.length && !r.rightAnchor) continue
                if (m.value.length < 3) continue
                if (best == null || m.value.length > best.length) best = m.value
            }
            return best
        }
    }
}
