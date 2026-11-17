package de.ostlertekker.ultraiptv

import de.ostlertekker.ultraiptv.core.discover.ChannelCountry
import de.ostlertekker.ultraiptv.core.discover.League
import de.ostlertekker.ultraiptv.core.discover.TvRights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvRightsTest {
    @Test fun laenderErkennung() {
        assertEquals("DE", ChannelCountry.detect("DE: Sky Sport Bundesliga 1 HD", "Sport"))
        assertEquals("PL", ChannelCountry.detect("|PL| Canal+ Sport 2", ""))
        assertEquals("UK", ChannelCountry.detect("UK ▎ TNT Sports 1 FHD", ""))
        assertEquals("PL", ChannelCountry.detect("Canal+ Sport", "Polska Sport"))
        assertEquals("MENA", ChannelCountry.detect("AR: beIN Sports 1", ""))
        assertNull(ChannelCountry.detect("Sky Sport News", "Sport"))
    }

    @Test fun senderNamenBereinigen() {
        val n = ChannelCountry.cleanName("DE: Sky Sport Bundesliga 1 FHD")
        assertTrue(n.contains(ChannelCountry.brandKey("Sky Sport")))
        assertTrue(ChannelCountry.cleanName("[PL] Canal+ Sport 3 HD").contains(ChannelCountry.brandKey("Canal+")))
        assertFalse(ChannelCountry.cleanName("DE: Sky Cinema Action HD").contains(ChannelCountry.brandKey("Sky Sport")))
    }

    @Test fun strengerSenderAbgleich() {
        fun m(ch: String, brand: String) = ChannelCountry.matches(ChannelCountry.cleanName(ch), ChannelCountry.brandKey(brand))
        assertTrue(m("DE: RTL HD", "RTL"))
        assertFalse(m("DE: RTL Nitro HD", "RTL"))
        assertFalse(m("DE: RTL Crime", "RTL"))
        assertFalse(m("DE: Super RTL", "RTL"))
        assertTrue(m("DE: Sky Sport Bundesliga 3 FHD", "Sky Sport"))
        assertFalse(m("DE: Sky Sport News", "Sky Sport"))
        assertTrue(m("|PL| Canal+ Sport 2", "Canal+"))
        assertFalse(m("|PL| Canal+ Film", "Canal+"))
        assertFalse(m("DE: ZDFneo", "ZDF"))
        // TV-Plan nennt einen konkreten Sender → Nummer muss passen
        val k = ChannelCountry.listingKey("Sky Sport Bundesliga 3 Germany")
        assertTrue(ChannelCountry.matches(ChannelCountry.cleanName("DE: Sky Sport Bundesliga 3 HD"), k))
        assertFalse(ChannelCountry.matches(ChannelCountry.cleanName("DE: Sky Sport Bundesliga 1 HD"), k))
        assertEquals("PL", ChannelCountry.fromCountryName("Poland"))
        assertEquals("UK", ChannelCountry.fromCountryName("United Kingdom"))
    }

    @Test fun rechteTabelle() {
        assertTrue(TvRights.holders(League.CHAMPIONS).contains("PL" to "Canal+"))
        assertTrue(TvRights.holders(League.PREMIER).contains("DE" to "Sky Sport"))
        assertTrue(TvRights.holders(League.F1).any { it.first == "US" && it.second == "Apple TV" })
        League.entries.forEach { assertTrue("Rechte fehlen: $it", TvRights.holders(it).isNotEmpty()) }
    }
}
