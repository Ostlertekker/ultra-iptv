package de.ostlertekker.ultraiptv

import de.ostlertekker.ultraiptv.feature.jellyfin.JfSearchFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JfSearchFilterTest {
    @Test fun typesAndYears() {
        assertEquals("Movie,Series", JfSearchFilter().types())
        assertEquals("Movie,Series,Episode", JfSearchFilter(query = "matrix").types())
        assertEquals("Movie", JfSearchFilter(query = "matrix", type = "Movie").types())
        assertEquals((2010..2019).toList(), JfSearchFilter(decade = 2010).years())
        assertEquals(1979, JfSearchFilter(decade = -1).years()!!.last())
        assertEquals(null, JfSearchFilter().years())
    }

    @Test fun defaultIgnoresQuery() {
        assertTrue(JfSearchFilter(query = "abc").isDefault)
        assertFalse(JfSearchFilter(query = "abc", genre = "Action").isDefault)
    }
}
