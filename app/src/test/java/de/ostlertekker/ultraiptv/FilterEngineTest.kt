package de.ostlertekker.ultraiptv

import de.ostlertekker.ultraiptv.feature.web.adblock.FilterEngine
import de.ostlertekker.ultraiptv.feature.web.adblock.FilterEngine.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterEngineTest {
    private val e = FilterEngine.build(listOf(sequenceOf(
        "[Adblock Plus 2.0]",
        "! Kommentar",
        "||ads.example.com^",
        "||tracker.net^\$third-party",
        "/banner/*/img^",
        "&ad_type=",
        "||cdn.site.org/ads/\$script,domain=news.de|~sport.news.de",
        "@@||ads.example.com/allowed.js",
        "||evil.com^\$important",
        "@@||evil.com^",
        "||popunder.biz^\$popup",
        "@@||friendly.org^\$document",
        "@@||nocosm.org^\$elemhide",
        "##.ad-banner",
        "###top-ad",
        "news.de##.sponsored",
        "~sport.news.de,news.de##.teaser-ad",
        "news.de#@#.ad-banner",
        "example.com#?#div:-abp-has(.x)",
        "/regex-rule/",
        "||csp.com^\$csp=script-src 'none'",
    )))

    @Test fun hostRules() {
        assertTrue(e.shouldBlock("https://ads.example.com/x.js", "ads.example.com", Type.SCRIPT, "site.de"))
        assertTrue(e.shouldBlock("https://sub.ads.example.com/x.png", "sub.ads.example.com", Type.IMAGE, "site.de"))
        assertFalse(e.shouldBlock("https://example.com/x.js", "example.com", Type.SCRIPT, "site.de"))
        assertFalse("Ausnahme", e.shouldBlock("https://ads.example.com/allowed.js", "ads.example.com", Type.SCRIPT, "site.de"))
        assertTrue("important schlägt Ausnahme", e.shouldBlock("https://evil.com/a", "evil.com", Type.OTHER, "site.de"))
    }

    @Test fun thirdPartyAndPatterns() {
        assertTrue(e.shouldBlock("https://tracker.net/p.gif", "tracker.net", Type.IMAGE, "site.de"))
        assertFalse(e.shouldBlock("https://tracker.net/p.gif", "tracker.net", Type.IMAGE, "www.tracker.net"))
        assertTrue(e.shouldBlock("https://x.com/banner/123/img?x=1", "x.com", Type.IMAGE, "site.de"))
        assertFalse(e.shouldBlock("https://x.com/banner/img.png", "x.com", Type.IMAGE, "site.de"))
        assertTrue(e.shouldBlock("https://x.com/q?a=1&ad_type=video", "x.com", Type.XHR, "site.de"))
    }

    @Test fun typesAndDomains() {
        assertTrue(e.shouldBlock("https://cdn.site.org/ads/a.js", "cdn.site.org", Type.SCRIPT, "www.news.de"))
        assertFalse(e.shouldBlock("https://cdn.site.org/ads/a.js", "cdn.site.org", Type.SCRIPT, "sport.news.de"))
        assertFalse(e.shouldBlock("https://cdn.site.org/ads/a.png", "cdn.site.org", Type.IMAGE, "www.news.de"))
        assertFalse(e.shouldBlock("https://cdn.site.org/ads/a.js", "cdn.site.org", Type.SCRIPT, "other.de"))
        // Hauptfenster nur mit $document/$popup
        assertFalse(e.shouldBlock("https://ads.example.com/", "ads.example.com", Type.DOCUMENT, null))
        assertTrue(e.shouldBlock("https://popunder.biz/x", "popunder.biz", Type.POPUP, "site.de"))
        assertFalse(e.shouldBlock("https://popunder.biz/x", "popunder.biz", Type.SCRIPT, "site.de"))
        assertEquals("unbekannte/unsichere Regeln übersprungen", false, e.shouldBlock("https://csp.com/", "csp.com", Type.SCRIPT, "a.de"))
    }

    @Test fun pageExceptionsAndCosmetics() {
        assertTrue(e.pageFlags("https://friendly.org/page", "friendly.org").allowAll)
        assertTrue(e.pageFlags("https://nocosm.org/", "nocosm.org").noElemHide)
        assertFalse(e.pageFlags("https://site.de/", "site.de").allowAll)
        val generic = e.genericCss.joinToString()
        assertTrue(generic.contains(".ad-banner") && generic.contains("#top-ad"))
        val css = e.specificCss("www.news.de")
        assertTrue(css.contains(".sponsored") && css.contains(".teaser-ad"))
        assertFalse(e.specificCss("sport.news.de").contains(".teaser-ad"))
        assertEquals(listOf(".ad-banner"), e.genericExceptionsFor("www.news.de"))
        assertFalse(css.contains(":-abp-has"))
    }

    @Test fun site() {
        assertEquals("example.com", FilterEngine.site("a.b.example.com"))
        assertEquals("bbc.co.uk", FilterEngine.site("www.bbc.co.uk"))
    }
}
