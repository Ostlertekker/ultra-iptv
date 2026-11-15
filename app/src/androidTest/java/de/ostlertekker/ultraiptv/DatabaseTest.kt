package de.ostlertekker.ultraiptv

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.ostlertekker.ultraiptv.core.database.ChannelEntity
import de.ostlertekker.ultraiptv.core.database.FavoriteEntity
import de.ostlertekker.ultraiptv.core.database.UltraDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Instrumentierter Test – auf Fire TV / Android-TV-Gerät ausführen: ./gradlew connectedDebugAndroidTest */
@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private lateinit var db: UltraDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, UltraDatabase::class.java).build()
    }

    @After fun tearDown() = db.close()

    @Test fun adultFilterAndFavorites() = runBlocking {
        db.content().upsertChannels(listOf(
            ChannelEntity("a", "Kanal A", "News", null, "http://x/a", "a", 1, false),
            ChannelEntity("b", "Kanal B", "XXX", null, "http://x/b", "b", 2, true),
        ))
        assertEquals(listOf("News"), db.content().channelCategories(false).first())
        assertEquals(2, db.content().channelCategories(true).first().size)
        db.user().addFavorite(FavoriteEntity(1, "LIVE", "a", 1))
        assertEquals(listOf("a"), db.user().favoriteIds(1, "LIVE").first())
    }
}
