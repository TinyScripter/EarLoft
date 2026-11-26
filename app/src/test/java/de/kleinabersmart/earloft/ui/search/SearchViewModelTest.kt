package de.kleinabersmart.earloft.ui.search

import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import de.kleinabersmart.earloft.core.data.repository.FavoritesRepository
import de.kleinabersmart.earloft.core.data.search.LibrarySearch
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.core.database.dao.MergeDao
import de.kleinabersmart.earloft.core.database.dao.SourceDao
import de.kleinabersmart.earloft.core.database.entity.AlbumEntity
import de.kleinabersmart.earloft.core.database.entity.SongEntity
import de.kleinabersmart.earloft.core.database.entity.SongSourceEntity
import de.kleinabersmart.earloft.core.database.entity.SourceEntity
import de.kleinabersmart.earloft.core.model.SourceType
import de.kleinabersmart.earloft.testing.FakePlaybackConnection
import de.kleinabersmart.earloft.testing.MainDispatcherRule
import de.kleinabersmart.earloft.testing.awaitReal
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/** Favorite marks of the search hits (review4 T50, 8.6 Y24). */
@HiltAndroidTest
@Config(application = HiltTestApplication::class)
@RunWith(RobolectricTestRunner::class)
class SearchViewModelTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val main = MainDispatcherRule()

    @Inject lateinit var librarySearch: LibrarySearch
    @Inject lateinit var spokenSettings: SpokenSettings
    @Inject lateinit var playback: FakePlaybackConnection
    @Inject lateinit var favorites: FavoritesRepository
    @Inject lateinit var mergeDao: MergeDao
    @Inject lateinit var sourceDao: SourceDao

    private val viewModel by lazy { SearchViewModel(SavedStateHandle(), librarySearch, spokenSettings, playback, favorites) }

    @Before
    fun setUp() {
        hilt.inject()
    }

    @Test
    fun `a favorite toggled in the hits follows later changes and new searches`() = runTest {
        sourceDao.upsert(SourceEntity("local", SourceType.LOCAL, "local"))
        val albumId = mergeDao.insertAlbum(AlbumEntity(title = "Dawn", sortTitle = "Dawn", normTitle = "dawn", albumArtistDisplay = "B", normAlbumArtist = "b"))
        val songId = mergeDao.insertSong(
            SongEntity(albumId = albumId, title = "Sunrise", normTitle = "sunrise", artistDisplay = "B", durationMs = 1, dateAdded = 0),
        )
        mergeDao.insertSongSource(SongSourceEntity(uid = "local:1", songId = songId, sourceId = "local", remoteId = "1", seenInGeneration = 1))
        viewModel.onQueryChange("sunrise")
        val hit = awaitReal { viewModel.results.first { it.results.songs.isNotEmpty() } }.results.songs.single()

        viewModel.toggleFavorite(hit)
        awaitReal { viewModel.results.first { it.results.songs.single().isFavorite } }

        // Removed again elsewhere (player, another device): before, the hits kept showing the star they had toggled.
        favorites.setFavorite(songId, false)
        awaitReal { viewModel.results.first { !it.results.songs.single().isFavorite } }

        viewModel.onQueryChange("sunris")
        awaitReal { viewModel.results.first { it.query == "sunris" && !it.results.songs.single().isFavorite } }
    }
}
