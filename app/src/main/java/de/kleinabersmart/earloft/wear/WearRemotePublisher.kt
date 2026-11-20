package de.kleinabersmart.earloft.wear

import androidx.core.graphics.scale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import android.provider.Settings
import android.os.Looper
import android.os.Handler
import android.database.ContentObserver
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
 * For remote control from the watch (Wear concept 15), keeps three DataItems up to date: what the phone is playing (music, book
 * or episode), its cover, and albums, artists, playlists, subscriptions and books. Written only while the watch has subscribed ([subscribe]) – otherwise every change
 * would wake the watch app, even if nobody is using it.
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

    /** The watch shows the remote control: the first time the phone rewrites everything immediately, afterwards on changes. */
    @Synchronized
    fun subscribe(scope: CoroutineScope) {
        Log.d(TAG, "Watch subscribed (active before: ${active.value})")
        expiry?.cancel()
        active.value = true
        expiry = scope.launch {
            delay(WearRemoteSubscription.TTL_MS)
            active.value = false
        }
    }

    /** Last written state; used to decide whether the sleep timer must be reported again. */
    @Volatile private var publishedState: WearRemoteStateDto? = null

    /** Last state of the sleep timer on the phone. */
    @Volatile private var sleep = SleepSnapshot(null, toEnd = false)

    /** [WearRemoteStateDto.coverKey] of the last written cover; only changes when the album or book changes. */
    private var publishedCoverKey: Long? = null

    /** Last written chapters ([WearRemoteChaptersDto]); rewritten only when they change. */
    private var publishedChapters: WearRemoteChaptersDto? = null

    /**
     * Timeline of the current book with the time it was read (`elapsedRealtime`), so not every report re-reads files and
     * chapters. After [TIMELINE_MAX_AGE_MS] it is read again: chapters can come later (episode downloaded while listening,
     * chapter list only on the second attempt), otherwise the watch would keep the old ones (review V5).
     */
    private var timelineCache: Triple<Long, BookTimeline?, Long>? = null

    /** Last written library index; `null` = still check with the Data Layer. */
    private var publishedLibraryHash: String? = null

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            active.flatMapLatest { subscribed ->
                if (!subscribed) {
                    emptyFlow()
                } else {
                    // The sleep timer reports every second; it is rewritten only on start, end or change, the watch
                    // counts down by itself.
                    val sleepChanges = combine(playback.sleepTimerRemainingMs, playback.sleepTimerToEnd, ::SleepSnapshot)
                        .onEach { sleep = it }
                        .filter { publishedState?.sleepTimerDiffers(System.currentTimeMillis(), it.remainingMs, it.toEnd) == true }
                        .map { }
                    // Combine state changes and jumps (seeking): a track change triggers both.
                    merge(playback.state.map { }, playback.positionJumps, sleepChanges, volumeChanges()).debounce(STATE_DEBOUNCE_MS)
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
                        // An artist's albums in the order of the album list: the first one provides its image on the watch.
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
                    // Most recently favorited first, as on the favorites page on the phone.
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
        // After a pause without subscription the watch may have an old cover: rewrite on a new subscription.
        val coverKey = dto.coverKey
        if (coverKey != null && coverKey != publishedCoverKey) publishCover(dto, coverKey)
        val bookId = dto.bookId
        if (dto.isSpoken && bookId != null) publishChapters(bookId)
    }

    /**
     * Once per book and when chapters change: the watch computes the chapter name from the position, the phone doesn't have to
     * report it on every change.
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
            volume = volumeOf(state),
            volumeMax = volumeMaxOf(state),
        )
    }

    /** Book or episode: position and length in the whole book, as in the app's book player. */
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
            volume = volumeOf(state),
            volumeMax = volumeMaxOf(state),
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

    /** Only when the content changed – a sync often changes the library without anything visible changing. */
    private suspend fun publishLibrary(dto: WearRemoteLibraryDto) {
        val hash = WearCodec.hash(WearCodec.encode(dto))
        if (publishedLibraryHash == null) publishedLibraryHash = dataClient.hashesAt(WearPaths.REMOTE_LIBRARY).values.firstOrNull()
        if (hash == publishedLibraryHash) return
        if (dataClient.putAssetPayload(WearPaths.REMOTE_LIBRARY, WearCodec.encodeCompressed(dto), hash)) {
            publishedLibraryHash = hash
            Log.d(
                TAG,
                "Remote control: ${dto.albums.size} albums, ${dto.artists.size} artists, ${dto.playlists.size} playlists, " +
                    "${dto.podcasts.size} subscriptions, ${dto.books.size} books",
            )
        }
    }

    /** While casting the cast device's volume (the crown sets it, review G50), otherwise the phone's music volume. */
    private fun volumeOf(state: PlayerState): Float {
        if (state.isRemote && state.maxDeviceVolume > 0) return state.deviceVolume / state.maxDeviceVolume.toFloat()
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (max > 0) audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat() else 0f
    }

    private fun volumeMaxOf(state: PlayerState): Int =
        if (state.isRemote && state.maxDeviceVolume > 0) state.maxDeviceVolume else audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    /**
     * Changes of the phone's volume (keys, other apps): they don't change the player state, so the watch showed the old value
     * until the next track (review G50). The system settings hold the stream volumes; only a changed value is reported.
     */
    private fun volumeChanges(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }.filter { publishedState?.let { it.volume != volumeOf(playback.state.value) } == true }

    /** Book covers come at 800 px; as with album covers, the watch only needs [maxPx] (and the 100 KB limit applies). */
    private fun Bitmap.scaledTo(maxPx: Int): Bitmap {
        val longest = maxOf(width, height)
        if (longest <= maxPx) return this
        val factor = maxPx.toFloat() / longest
        return scale((width * factor).toInt().coerceAtLeast(1), (height * factor).toInt().coerceAtLeast(1))
    }

    private fun Bitmap.toJpeg(): ByteArray =
        ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()

    private companion object {
        const val TAG = "WearRemotePublisher"
        const val STATE_DEBOUNCE_MS = 250L
        const val LIBRARY_DEBOUNCE_MS = 3_000L
        const val TIMELINE_MAX_AGE_MS = 60_000L

        /** The watch shows the cover in a circle of about 400 px; 300 px stay well below the 100 KB limit with JPEG. */
        const val COVER_SIZE_PX = 300
        const val JPEG_QUALITY = 80
    }
}

/** Chapters of the book [bookId] for the remote control (book times); without timeline or chapters an empty list. */
internal fun remoteChapters(bookId: Long, timeline: BookTimeline?): WearRemoteChaptersDto = WearRemoteChaptersDto(
    bookId = bookId,
    chapters = timeline?.chapters.orEmpty().map { WearChapterDto(it.title, it.startMs, it.endMs, it.unavailable) },
)

/** Remaining time of the sleep timer on the phone and whether it runs until the end of the chapter or episode. */
private data class SleepSnapshot(val remainingMs: Long?, val toEnd: Boolean)
