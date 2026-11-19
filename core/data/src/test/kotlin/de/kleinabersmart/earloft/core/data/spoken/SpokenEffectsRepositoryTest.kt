package de.kleinabersmart.earloft.core.data.spoken

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookProgressEntity
import de.kleinabersmart.earloft.core.database.entity.PodcastFeedEntity
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.EffectOverrides
import de.kleinabersmart.earloft.core.model.LOCAL_BOOK_SERVER_ID
import de.kleinabersmart.earloft.core.model.SpokenEffects
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Speed and effects: episode or book > subscription > section, own values only as long as they differ (concept 12.4). */
@RunWith(RobolectricTestRunner::class)
class SpokenEffectsRepositoryTest {

    private lateinit var db: EarLoftDatabase
    private lateinit var settings: SpokenSettings
    private lateinit var repository: SpokenEffectsRepository
    private var episodeId = 0L
    private var bookId = 0L

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, EarLoftDatabase::class.java).allowMainThreadQueries().build()
        settings = SpokenSettings(context)
        // The DataStore persists between tests within the process.
        settings.setBookEffects(SpokenEffects.DEFAULT)
        settings.setPodcastEffects(SpokenEffects.DEFAULT)
        repository = SpokenEffectsRepository(db.bookDao(), db.podcastDao(), settings)
        db.podcastDao().upsertFeed(PodcastFeedEntity(feedId = FEED_ID, url = "https://example.org/feed", title = "Podcast", speed = 1.5f, skipIntroS = 30))
        episodeId = db.bookDao().upsertBook(book(FEED_ID, BookKind.EPISODE, "folge"))
        bookId = db.bookDao().upsertBook(book(LOCAL_BOOK_SERVER_ID, BookKind.BOOK, "buch"))
        db.bookDao().upsertProgress(BookProgressEntity(episodeId, positionMs = 0, durationMs = HOUR_MS, updatedAt = 1))
        db.bookDao().upsertProgress(BookProgressEntity(bookId, positionMs = 0, durationMs = HOUR_MS, updatedAt = 1))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `an episode follows its feed, then the area`() = runTest {
        settings.setPodcastEffects(SpokenEffects(speed = 1.2f, skipSilence = true, volumeBoostDb = 3))

        val effects = repository.effectsNow(episodeId)

        assertEquals(SpokenEffects(1.5f, skipSilence = true, volumeBoostDb = 3, skipIntroMs = 30_000), effects)
    }

    @Test
    fun `a speed equal to the inherited one is not stored`() = runTest {
        repository.setSpeed(episodeId, 1.25f)
        assertEquals(1.25f, db.bookDao().progress(episodeId)?.speed)

        repository.setSpeed(episodeId, 1.5f)
        assertNull(db.bookDao().progress(episodeId)?.speed)
        assertEquals(1.5f, repository.effectsNow(episodeId).speed)
    }

    @Test
    fun `remembering an episode's values sets the feed and clears its own`() = runTest {
        repository.setSpeed(episodeId, 2f)
        repository.setVolumeBoost(episodeId, 6)

        repository.rememberAsDefault(episodeId)

        val feed = db.podcastDao().feed(FEED_ID)!!
        assertEquals(EffectOverrides(speed = 2f, volumeBoostDb = 6), EffectOverrides(feed.speed, feed.skipSilence, feed.volumeBoostDb))
        val own = db.bookDao().progress(episodeId)!!
        assertEquals(EffectOverrides(), EffectOverrides(own.speed, own.skipSilence, own.volumeBoostDb))
        assertEquals(2f, repository.effectsNow(episodeId).speed)
    }

    @Test
    fun `remembering a book's values sets the default for books`() = runTest {
        repository.setSkipSilence(bookId, true)
        assertEquals(true, db.bookDao().progress(bookId)?.skipSilence)

        repository.rememberAsDefault(bookId)

        assertEquals(SpokenEffects(skipSilence = true), settings.bookEffects.first())
        assertNull(db.bookDao().progress(bookId)?.skipSilence)
        // Episodes stay unaffected by it.
        assertEquals(false, repository.effectsNow(episodeId).skipSilence)
    }

    @Test
    fun `a changed area default reaches books without own values`() = runTest {
        repository.setSpeed(bookId, 1.75f)
        val other = db.bookDao().upsertBook(book(LOCAL_BOOK_SERVER_ID, BookKind.BOOK, "anderes"))

        settings.setBookEffects(SpokenEffects(speed = 1.3f))

        assertEquals(1.75f, repository.effectsNow(bookId).speed)
        assertEquals(1.3f, repository.effectsNow(other).speed)
    }

    private fun book(serverId: String, kind: BookKind, remoteId: String) = BookEntity(
        serverId = serverId,
        kind = kind,
        remoteId = remoteId,
        title = remoteId,
        normTitle = remoteId,
        author = "",
        durationMs = HOUR_MS,
    )

    private companion object {
        const val FEED_ID = "feed"
        const val HOUR_MS = 3_600_000L
    }
}
