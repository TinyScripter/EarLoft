package de.kleinabersmart.earloft.wear.sync

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.data.sync.CollectionSourceResolver
import de.kleinabersmart.earloft.core.data.sync.LibrarySyncer
import de.kleinabersmart.earloft.core.data.sync.MergeResolver
import de.kleinabersmart.earloft.core.data.sync.PlaylistSyncer
import de.kleinabersmart.earloft.core.data.sync.SyncNotices
import de.kleinabersmart.earloft.core.data.sync.SyncQueueStarter
import de.kleinabersmart.earloft.core.data.sync.SyncScheduler
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.entity.DownloadEntity
import de.kleinabersmart.earloft.core.database.entity.SourceEntity
import de.kleinabersmart.earloft.core.model.DownloadState
import de.kleinabersmart.earloft.core.model.SourceType
import de.kleinabersmart.earloft.core.model.WearSelectionKind
import de.kleinabersmart.earloft.core.wearsync.PhoneFileDto
import de.kleinabersmart.earloft.core.wearsync.WearManifest
import de.kleinabersmart.earloft.core.wearsync.WearSelectionDto
import de.kleinabersmart.earloft.core.wearsync.WearServerSourceDto
import de.kleinabersmart.earloft.core.wearsync.WearSongDto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The watch restores the phone's songs from the manifest (Wear concept 7.1). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ManifestApplierTest {

    private lateinit var db: EarLoftDatabase
    private lateinit var applier: ManifestApplier

    /** Compilation track on two servers – one song on the phone. */
    private val sampler = song(
        "nd:s1", "Erster Schritt", album = "Sampler 2023", albumArtist = "Verschiedene Künstler", artist = "Band A",
        servers = listOf(server("nd", "s1"), server("office", "o1")), isCompilation = true,
    )
    private val sampler2 = song(
        "nd:s2", "Zweiter Schritt", album = "Sampler 2023", albumArtist = "Verschiedene Künstler", artist = "Band B",
        servers = listOf(server("nd", "s2")), isCompilation = true, track = 2,
    )

    /** Albums with the same name by different artists with an identically named first track. */
    private val introA = song("nd:a1", "Intro", album = "Greatest Hits", albumArtist = "Alpha", servers = listOf(server("nd", "a1")))
    private val introB = song("nd:b1", "Intro", album = "Greatest Hits", albumArtist = "Beta", servers = listOf(server("nd", "b1")))

    /** Only on the phone. */
    private val summer = song(
        "local:5", "Sommerlied", album = "Lokal", albumArtist = "Ich", servers = emptyList(),
        phoneFile = PhoneFileDto("local:5", 4_000, "audio/mpeg"),
    )

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EarLoftDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val sourceDao = db.sourceDao()
        sourceDao.upsert(SourceEntity("nd", SourceType.SUBSONIC, "Navidrome"))
        sourceDao.upsert(SourceEntity("office", SourceType.SUBSONIC, "Büro"))
        val syncer = LibrarySyncer(
            database = db,
            sourceDao = sourceDao,
            mergeDao = db.mergeDao(),
            libraryDao = db.libraryDao(),
            mergeResolver = MergeResolver(db.mergeDao(), db.syncDao(), CollectionSourceResolver(db.mergeDao(), db.syncDao())),
            changeListeners = emptySet(),
            syncQueue = SyncQueueStarter(db.syncDao(), NoScheduler),
            playlistSyncer = PlaylistSyncer(db, db.playlistDao(), db.syncDao(), SyncNotices()),
            syncDao = db.syncDao(),
        )
        applier = ManifestApplier(db, sourceDao, db.wearDao(), db.playlistDao(), syncer)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `server sources of one phone song become one song, same-named albums stay apart`() = runTest {
        applier.apply(manifest(listOf("album:1" to listOf(sampler, sampler2)), extra = listOf(introA, introB)))

        assertEquals(songId("nd:s1"), songId("office:o1"))
        assertEquals(4, songCount())
        assertNotEquals(songId("nd:a1"), songId("nd:b1"))
        assertNotEquals(albumId("nd:a1"), albumId("nd:b1"))
        assertEquals(albumId("nd:s1"), albumId("nd:s2"))
    }

    @Test
    fun `selections become device playlists in order with duplicates`() = runTest {
        applier.apply(
            manifest(
                listOf(
                    "playlist:7" to listOf(introA, sampler, introA),
                    "favorites" to listOf(summer),
                ),
            ),
        )

        val selections = db.wearDao().getSelections()
        assertEquals(listOf("playlist:7", "favorites"), selections.map { it.key })
        val mix = db.playlistDao().getPlaylist(selections[0].refId!!)!!
        assertEquals("playlist:7", mix.name)
        assertNull(mix.boundSourceId)
        assertEquals(listOf(songId("nd:a1"), songId("nd:s1"), songId("nd:a1")), db.playlistDao().getSongIds(mix.playlistId))
        assertEquals(listOf(songId("phone:local:5")), db.playlistDao().getSongIds(selections[1].refId!!))
        assertEquals(SourceType.PHONE, db.sourceDao().get("phone")!!.type)
    }

    @Test
    fun `songs and selections leaving the manifest are removed, others keep their ids`() = runTest {
        applier.apply(manifest(listOf("album:1" to listOf(sampler, sampler2), "favorites" to listOf(summer))))
        val samplerId = songId("nd:s1")
        val (playlistId, favoritesPlaylistId) = db.wearDao().getSelections().map { it.refId!! }

        applier.apply(manifest(listOf("album:1" to listOf(sampler))))

        assertEquals(samplerId, songId("nd:s1"))
        assertEquals(1, songCount())
        assertEquals(listOf("album:1"), db.wearDao().getSelections().map { it.key })
        assertEquals(playlistId, db.wearDao().getSelections().single().refId)
        assertEquals(1, db.playlistDao().getSongIds(playlistId).size)
        assertNull(db.playlistDao().getPlaylist(favoritesPlaylistId))
        assertNull(db.syncDao().getSongSource("phone:local:5"))
    }

    @Test
    fun `a song loaded over the phone keeps its download when the watch learns its server`() = runTest {
        // On Jellyfin on the phone, the watch does not know the server yet (older watch app): it downloaded the file via the phone.
        val onJellyfin = summer.copy(serverSources = listOf(server("jf", "x")))
        applier.apply(manifest(listOf("album:9" to listOf(onJellyfin))))
        val id = songId("phone:local:5")
        db.downloadDao().insertIfAbsent(listOf(DownloadEntity("phone:local:5", id, DownloadState.DONE, filePath = "/x/a.mp3")))

        db.sourceDao().upsert(SourceEntity("jf", SourceType.JELLYFIN, "Jellyfin"))
        applier.apply(manifest(listOf("album:9" to listOf(onJellyfin))))

        // Both sources belong to the same song; the download via the phone stays (otherwise the sync would delete the file).
        assertEquals(id, songId("jf:x"))
        assertEquals(DownloadState.DONE, db.downloadDao().get("phone:local:5")?.state)
    }

    @Test
    fun `changed song metadata is updated in place`() = runTest {
        applier.apply(manifest(listOf("album:1" to listOf(introA))))
        val id = songId("nd:a1")

        applier.apply(manifest(listOf("album:1" to listOf(introA.copy(title = "Intro (Remaster)")))))

        assertEquals(id, songId("nd:a1"))
        assertEquals("Intro (Remaster)", db.mergeDao().getSong(id)!!.title)
    }

    private suspend fun songId(uid: String) = db.syncDao().getSongSource(uid)!!.songId

    private suspend fun albumId(uid: String) = db.mergeDao().getSong(songId(uid))!!.albumId

    private suspend fun songCount() = db.libraryDao().observeSongRows().first().size

    private fun manifest(selections: List<Pair<String, List<WearSongDto>>>, extra: List<WearSongDto> = emptyList()) = WearManifest(
        selections = selections.map { (key, songs) ->
            val kind = when {
                key.startsWith("album:") -> WearSelectionKind.ALBUM
                key.startsWith("playlist:") -> WearSelectionKind.PLAYLIST
                else -> WearSelectionKind.FAVORITES
            }
            WearSelectionDto(key, kind, title = key, songKeys = songs.map { it.key })
        },
        songs = (selections.flatMap { it.second } + extra).distinctBy { it.key },
    )

    private fun song(
        key: String,
        title: String,
        album: String,
        albumArtist: String,
        servers: List<WearServerSourceDto>,
        artist: String = albumArtist,
        isCompilation: Boolean = false,
        track: Int = 1,
        phoneFile: PhoneFileDto? = null,
    ) = WearSongDto(
        key = key,
        title = title,
        artists = listOf(artist),
        artistDisplay = artist,
        album = album,
        albumArtist = albumArtist,
        isCompilation = isCompilation,
        trackNumber = track,
        durationMs = 200_000,
        serverSources = servers,
        phoneFile = phoneFile,
    )

    private fun server(sourceId: String, remoteId: String) =
        WearServerSourceDto("$sourceId:$remoteId", sourceId, remoteId, mimeType = "audio/flac", sizeBytes = 10_000)

    private object NoScheduler : SyncScheduler {
        override suspend fun enqueue() = Unit
    }
}
