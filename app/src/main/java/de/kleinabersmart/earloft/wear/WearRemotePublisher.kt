package de.kleinabersmart.earloft.wear

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.wearable.DataClient
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.data.repository.AlbumColorRepository
import de.kleinabersmart.earloft.core.data.repository.ArtworkRepository
import de.kleinabersmart.earloft.core.data.repository.FavoritesRepository
import de.kleinabersmart.earloft.core.data.repository.LibraryRepository
import de.kleinabersmart.earloft.core.data.repository.PlaylistRepository
import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.data.spoken.BookCovers
import de.kleinabersmart.earloft.core.data.spoken.BookRepository
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.BookTimeline
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
import de.kleinabersmart.earloft.core.playback.PlayerState
import de.kleinabersmart.earloft.core.wearsync.WearCodec
import de.kleinabersmart.earloft.core.wearsync.WearPaths
import de.kleinabersmart.earloft.core.wearsync.WearChapterDto
import de.kleinabersmart.earloft.core.wearsync.WearRemoteAlbumDto
import de.kleinabersmart.earloft.core.wearsync.WearRemoteChaptersDto
import de.kleinabersmart.earloft.core.wearsync.WearRemoteArtistDto
import de.kleinabersmart.earloft.core.wearsync.WearRemotePodcastDto
import de.kleinabersmart.earloft.core.wearsync.WearRemoteLibraryDto
import de.kleinabersmart.earloft.core.wearsync.WearRemoteMediaKind
import de.kleinabersmart.earloft.core.wearsync.WearRemotePlaylistDto
import de.kleinabersmart.earloft.core.wearsync.WearRemoteStateDto
import de.kleinabersmart.earloft.core.wearsync.WearRemoteSubscription
import de.kleinabersmart.earloft.core.wearsync.hashesAt
import de.kleinabersmart.earloft.core.wearsync.putAssetPayload
import de.kleinabersmart.earloft.core.wearsync.putPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hält für die Fernsteuerung von der Uhr aus (Wear-Konzept 15) drei DataItems aktuell: was das Handy spielt (Musik, Buch
 * oder Folge), dessen Cover sowie Alben, Künstler, Playlists, Abos und Bücher. Geschrieben wird nur, solange die Uhr sich angemeldet hat ([subscribe]) – jede Änderung
 * weckt sonst die Uhr-App, auch wenn niemand sie benutzt.
 */
@Singleton
class WearRemotePublisher @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dataClient: DataClient,
    private val playback: PlaybackConnection,
    private val artwork: ArtworkRepository,
    private val albumColors: AlbumColorRepository,
    private val library: LibraryRepository,
    private val playlists: PlaylistRepository,
    private val favorites: FavoritesRepository,
    private val books: BookRepository,
    private val bookCovers: BookCovers,
    private val podcasts: PodcastRepository,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val active = MutableStateFlow(false)
    private var expiry: Job? = null

    /** Die Uhr zeigt die Fernsteuerung: Beim ersten Mal schreibt das Handy sofort alles neu, danach bei Änderungen. */
    @Synchronized
    fun subscribe(scope: CoroutineScope) {
        Log.d(TAG, "Uhr angemeldet (aktiv bisher: ${active.value})")
        expiry?.cancel()
        active.value = true
        expiry = scope.launch {
            delay(WearRemoteSubscription.TTL_MS)
            active.value = false
        }
    }

    /** Zuletzt geschriebener Zustand; daran misst sich, ob der Sleep-Timer neu gemeldet werden muss. */
    @Volatile private var publishedState: WearRemoteStateDto? = null

    /** Letzter Stand des Sleep-Timers am Handy. */
    @Volatile private var sleep = SleepSnapshot(null, toEnd = false)

    /** [WearRemoteStateDto.coverKey] des zuletzt geschriebenen Covers; ändert sich nur beim Album- bzw. Buchwechsel. */
    private var publishedCoverKey: Long? = null

    /** Zuletzt geschriebene Kapitel ([WearRemoteChaptersDto]); neu geschrieben wird nur, wenn sie sich ändern. */
    private var publishedChapters: WearRemoteChaptersDto? = null

    /**
     * Zeitachse des laufenden Buchs mit dem Zeitpunkt des Lesens (`elapsedRealtime`), damit nicht jede Meldung Dateien und
     * Kapitel neu liest. Nach [TIMELINE_MAX_AGE_MS] wird sie neu gelesen: Kapitel können später kommen (Folge beim Hören
     * geladen, Kapitel-Liste erst im zweiten Anlauf), die Uhr behielte sonst die alten (Review V5).
     */
    private var timelineCache: Triple<Long, BookTimeline?, Long>? = null

    /** Zuletzt geschriebener Bibliotheksindex; `null` = noch beim Data Layer nachsehen. */
    private var publishedLibraryHash: String? = null

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            active.flatMapLatest { subscribed ->
                if (!subscribed) {
                    emptyFlow()
                } else {
                    // Der Sleep-Timer meldet sich jede Sekunde; neu geschrieben wird nur bei Start, Ende oder Änderung, die Uhr
                    // zählt selbst herunter.
                    val sleepChanges = combine(playback.sleepTimerRemainingMs, playback.sleepTimerToEnd, ::SleepSnapshot)
                        .onEach { sleep = it }
                        .filter { publishedState?.sleepTimerDiffers(System.currentTimeMillis(), it.remainingMs, it.toEnd) == true }
                        .map { }
                    // Zustandsänderungen und Sprünge (Spulen) zusammenfassen: Ein Titelwechsel löst beides aus.
                    merge(playback.state.map { }, playback.positionJumps, sleepChanges).debounce(STATE_DEBOUNCE_MS)
                }
            }.collect { publishState() }
        }
        scope.launch {
            active.flatMapLatest { subscribed ->
                if (!subscribed) {
                    emptyFlow()
                } else {
                    var first = true
                    val music = combine(
                        library.albums(),
                        playlists.playlists(),
                        favorites.favorites(),
                        library.artists(),
                        library.albumIdsByArtist(),
                    ) { albums, lists, favs, artists, albumIdsByArtist ->
                        // Die Alben eines Künstlers in der Reihenfolge der Albumliste: Das erste liefert sein Bild auf der Uhr.
                        val albumOrder = albums.withIndex().associate { it.value.id to it.index }
                        WearRemoteLibraryDto(
                            albums = albums.map { WearRemoteAlbumDto(it.id, it.title, it.artist, it.songCount) },
                            playlists = lists.map { WearRemotePlaylistDto(it.id, it.name, it.songCount) },
                            favoritesCount = favs.size,
                            artists = artists.map { artist ->
                                val albumIds = albumIdsByArtist[artist.id].orEmpty().filter { it in albumOrder }.sortedBy { albumOrder[it] }
                                WearRemoteArtistDto(artist.id, artist.name, albumIds)
                            },
                        )
                    }
                    // Zuletzt favorisierte zuerst, wie auf der Favoriten-Seite am Handy.
                    val collectionFavorites = combine(favorites.favoriteAlbums(), favorites.favoriteArtists()) { albums, artists ->
                        albums.map { it.id } to artists.map { it.id }
                    }
                    val spoken = combine(podcasts.feeds, books.books()) { feeds, allBooks ->
                        feeds.map { WearRemotePodcastDto(it.id, it.title, it.author) } to remoteBooks(allBooks)
                    }
                    combine(music, collectionFavorites, spoken) { dto, (favoriteAlbums, favoriteArtists), (feeds, bookList) ->
                        dto.copy(
                            favoriteAlbumIds = favoriteAlbums,
                            favoriteArtistIds = favoriteArtists,
                            podcasts = feeds,
                            books = bookList,
                            hasPodcastQueue = true,
                        )
                    }.debounce { if (first) 0L.also { first = false } else LIBRARY_DEBOUNCE_MS }
                }
            }.collect { publishLibrary(it) }
        }
    }

    private suspend fun publishState() {
        val state = playback.state.value
        val positionInTrack = withContext(Dispatchers.Main) { playback.currentPositionMs }
        val sleepNow = sleep
        val dto = (if (state.spoken != null) spokenState(state, positionInTrack) else musicState(state, positionInTrack))
            .copy(sleepTimerRemainingMs = sleepNow.remainingMs, sleepTimerToEnd = sleepNow.toEnd, sleepTimerSupported = true)
        dataClient.putPayload(WearPaths.REMOTE_STATE, WearCodec.encode(dto))
        publishedState = dto
        // Nach einer Pause ohne Anmeldung kann die Uhr ein altes Cover haben: bei neuer Anmeldung neu schreiben.
        val coverKey = dto.coverKey
        if (coverKey != null && coverKey != publishedCoverKey) publishCover(dto, coverKey)
        val bookId = dto.bookId
        if (dto.isSpoken && bookId != null) publishChapters(bookId)
    }

    /**
     * Einmal je Buch und bei geänderten Kapiteln: Den Kapitelnamen rechnet die Uhr aus der Position, das Handy muss ihn nicht
     * bei jedem Wechsel melden.
     */
    private suspend fun publishChapters(bookId: Long) {
        val dto = remoteChapters(bookId, timeline(bookId))
        if (dto == publishedChapters) return
        if (dataClient.putPayload(WearPaths.REMOTE_CHAPTERS, WearCodec.encodeCompressed(dto))) publishedChapters = dto
    }

    private suspend fun musicState(state: PlayerState, positionMs: Long): WearRemoteStateDto {
        val current = state.current
        val albumId = current?.albumId
        return WearRemoteStateDto(
            hasMedia = current != null,
            title = current?.title.orEmpty(),
            artist = current?.artist.orEmpty(),
            albumId = albumId,
            albumTitle = current?.albumTitle.orEmpty(),
            durationMs = state.durationMs,
            positionMs = positionMs,
            sampledAt = System.currentTimeMillis(),
            isPlaying = state.isPlaying,
            shuffle = state.shuffle,
            repeatMode = state.repeatMode,
            seedColor = albumId?.let { albumColors.seedColorNow(it) },
            volume = musicVolume(),
            volumeMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
        )
    }

    /** Buch oder Folge: Position und Länge im ganzen Buch, wie im Buch-Player der App. */
    private suspend fun spokenState(state: PlayerState, positionInTrackMs: Long): WearRemoteStateDto {
        val spoken = checkNotNull(state.spoken)
        val timeline = timeline(spoken.bookId)
        val kind = if (books.book(spoken.bookId).first()?.book?.kind == BookKind.EPISODE) WearRemoteMediaKind.EPISODE else WearRemoteMediaKind.BOOK
        return WearRemoteStateDto(
            hasMedia = true,
            kind = kind,
            title = spoken.title,
            artist = spoken.author,
            bookId = spoken.bookId,
            durationMs = timeline?.durationMs?.takeIf { it > 0 } ?: state.durationMs,
            positionMs = timeline?.toBookPosition(spoken.trackIndex, positionInTrackMs) ?: positionInTrackMs,
            sampledAt = System.currentTimeMillis(),
            isPlaying = state.isPlaying,
            speed = state.speed,
            seekBackSeconds = spoken.seek.backSeconds,
            seekForwardSeconds = spoken.seek.forwardSeconds,
            seedColor = bookCovers.seedColor(spoken.bookId),
            volume = musicVolume(),
            volumeMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
        )
    }

    private suspend fun timeline(bookId: Long): BookTimeline? {
        val now = SystemClock.elapsedRealtime()
        timelineCache?.takeIf { (cachedId, _, readAt) -> cachedId == bookId && now - readAt < TIMELINE_MAX_AGE_MS }?.let { return it.second }
        return books.timeline(bookId).also { timelineCache = Triple(bookId, it, now) }
    }

    private suspend fun publishCover(dto: WearRemoteStateDto, coverKey: Long) {
        val bookId = dto.bookId
        val bitmap = if (dto.isSpoken && bookId != null) {
            bookCovers.cover(bookId)
        } else {
            dto.albumId?.let { artwork.albumCover(it, COVER_SIZE_PX) }
        }
        val jpeg = bitmap?.let { withContext(Dispatchers.Default) { it.scaledTo(COVER_SIZE_PX).toJpeg() } } ?: ByteArray(0)
        if (dataClient.putPayload(WearPaths.REMOTE_COVER, WearCodec.encodeCover(coverKey, jpeg))) publishedCoverKey = coverKey
    }

    /** Nur bei geändertem Inhalt – ein Abgleich ändert die Bibliothek oft, ohne dass sich etwas Sichtbares ändert. */
    private suspend fun publishLibrary(dto: WearRemoteLibraryDto) {
        val hash = WearCodec.hash(WearCodec.encode(dto))
        if (publishedLibraryHash == null) publishedLibraryHash = dataClient.hashesAt(WearPaths.REMOTE_LIBRARY).values.firstOrNull()
        if (hash == publishedLibraryHash) return
        if (dataClient.putAssetPayload(WearPaths.REMOTE_LIBRARY, WearCodec.encodeCompressed(dto), hash)) {
            publishedLibraryHash = hash
            Log.d(
                TAG,
                "Fernsteuerung: ${dto.albums.size} Alben, ${dto.artists.size} Künstler, ${dto.playlists.size} Playlists, " +
                    "${dto.podcasts.size} Abos, ${dto.books.size} Bücher",
            )
        }
    }

    private fun musicVolume(): Float {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (max > 0) audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat() else 0f
    }

    /** Buchcover kommen mit 800 px; die Uhr braucht wie bei Albumcovern nur [maxPx] (und die 100-KB-Grenze gilt). */
    private fun Bitmap.scaledTo(maxPx: Int): Bitmap {
        val longest = maxOf(width, height)
        if (longest <= maxPx) return this
        val factor = maxPx.toFloat() / longest
        return Bitmap.createScaledBitmap(this, (width * factor).toInt().coerceAtLeast(1), (height * factor).toInt().coerceAtLeast(1), true)
    }

    private fun Bitmap.toJpeg(): ByteArray =
        ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()

    private companion object {
        const val TAG = "WearRemotePublisher"
        const val STATE_DEBOUNCE_MS = 250L
        const val LIBRARY_DEBOUNCE_MS = 3_000L
        const val TIMELINE_MAX_AGE_MS = 60_000L

        /** Die Uhr zeigt das Cover im Kreis von rund 400 px; 300 px bleiben mit JPEG deutlich unter der 100-KB-Grenze. */
        const val COVER_SIZE_PX = 300
        const val JPEG_QUALITY = 80
    }
}

/** Kapitel des Buchs [bookId] für die Fernsteuerung (Buchzeiten); ohne Zeitachse oder Kapitel eine leere Liste. */
internal fun remoteChapters(bookId: Long, timeline: BookTimeline?): WearRemoteChaptersDto = WearRemoteChaptersDto(
    bookId = bookId,
    chapters = timeline?.chapters.orEmpty().map { WearChapterDto(it.title, it.startMs, it.endMs, it.unavailable) },
)

/** Restzeit des Sleep-Timers am Handy und ob er bis zum Ende des Kapitels bzw. der Folge läuft. */
private data class SleepSnapshot(val remainingMs: Long?, val toEnd: Boolean)
