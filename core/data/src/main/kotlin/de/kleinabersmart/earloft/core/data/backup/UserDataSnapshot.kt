package de.kleinabersmart.earloft.core.data.backup

import de.kleinabersmart.earloft.core.data.spoken.encodeArdParts
import de.kleinabersmart.earloft.core.database.dao.KeyedBook
import de.kleinabersmart.earloft.core.database.dao.KeyedBookPart
import de.kleinabersmart.earloft.core.database.dao.KeyedBookmark
import de.kleinabersmart.earloft.core.database.dao.KeyedCollectionFavorite
import de.kleinabersmart.earloft.core.database.dao.KeyedEpisodeDownload
import de.kleinabersmart.earloft.core.database.dao.KeyedFavorite
import de.kleinabersmart.earloft.core.database.dao.KeyedPlayEvent
import de.kleinabersmart.earloft.core.database.dao.KeyedPlaylistEntry
import de.kleinabersmart.earloft.core.database.dao.KeyedProgress
import de.kleinabersmart.earloft.core.database.dao.KeyedSong
import de.kleinabersmart.earloft.core.database.dao.KeyedStarredEpisode
import de.kleinabersmart.earloft.core.database.dao.KeyedWearBook
import de.kleinabersmart.earloft.core.database.dao.RowChanges
import de.kleinabersmart.earloft.core.database.dao.UserDataChanges
import de.kleinabersmart.earloft.core.database.dao.UserDataDao
import de.kleinabersmart.earloft.core.database.entity.BookProgressEntity
import de.kleinabersmart.earloft.core.database.entity.BookmarkEntity
import de.kleinabersmart.earloft.core.database.entity.ListeningDayEntity
import de.kleinabersmart.earloft.core.database.entity.ListeningStatEntity
import de.kleinabersmart.earloft.core.database.entity.PlaylistEntity
import de.kleinabersmart.earloft.core.database.entity.PodcastFeedEntity
import de.kleinabersmart.earloft.core.database.entity.SourceEntity
import de.kleinabersmart.earloft.core.database.entity.SpokenServerEntity
import de.kleinabersmart.earloft.core.database.entity.UserArdBookEntity
import de.kleinabersmart.earloft.core.database.entity.UserBookProgressEntity
import de.kleinabersmart.earloft.core.database.entity.UserBookmarkEntity
import de.kleinabersmart.earloft.core.database.entity.UserCollectionFavoriteEntity
import de.kleinabersmart.earloft.core.database.entity.UserEpisodeDownloadEntity
import de.kleinabersmart.earloft.core.database.entity.UserEpisodeStarEntity
import de.kleinabersmart.earloft.core.database.entity.UserFavoriteEntity
import de.kleinabersmart.earloft.core.database.entity.UserListeningDayEntity
import de.kleinabersmart.earloft.core.database.entity.UserListeningStatEntity
import de.kleinabersmart.earloft.core.database.entity.UserPlayEventEntity
import de.kleinabersmart.earloft.core.database.entity.UserPlaylistEntity
import de.kleinabersmart.earloft.core.database.entity.UserPlaylistEntryEntity
import de.kleinabersmart.earloft.core.database.entity.UserPodcastEntity
import de.kleinabersmart.earloft.core.database.entity.UserServerEntity
import de.kleinabersmart.earloft.core.database.entity.UserSongKey
import de.kleinabersmart.earloft.core.database.entity.UserSpokenServerEntity
import de.kleinabersmart.earloft.core.database.entity.UserWearBookEntity
import de.kleinabersmart.earloft.core.database.entity.UserWearSelectionEntity
import de.kleinabersmart.earloft.core.model.FavoriteKind
import de.kleinabersmart.earloft.core.model.LOCAL_PODCAST_PREFIX
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.core.model.hasNoBookServer

/** The contents of `user.db` (or what belongs in it), per table by its stable key. */
data class UserDataSnapshot(
    val servers: Map<String, UserServerEntity> = emptyMap(),
    val spokenServers: Map<String, UserSpokenServerEntity> = emptyMap(),
    val podcasts: Map<String, UserPodcastEntity> = emptyMap(),
    val listeningStats: Map<StatKey, UserListeningStatEntity> = emptyMap(),
    val listeningDays: Map<DayKey, UserListeningDayEntity> = emptyMap(),
    val progress: Map<BookKey, UserBookProgressEntity> = emptyMap(),
    val playlists: Map<String, UserPlaylistEntity> = emptyMap(),
    val playlistEntries: Map<EntryKey, UserPlaylistEntryEntity> = emptyMap(),
    val favorites: Map<UserSongKey, UserFavoriteEntity> = emptyMap(),
    val playEvents: Map<PlayKey, UserPlayEventEntity> = emptyMap(),
    val wearSelections: Map<String, UserWearSelectionEntity> = emptyMap(),
    val episodeDownloads: Map<BookKey, UserEpisodeDownloadEntity> = emptyMap(),
    val collectionFavorites: Map<CollectionKey, UserCollectionFavoriteEntity> = emptyMap(),
    val bookmarks: Map<BookmarkKey, UserBookmarkEntity> = emptyMap(),
    val episodeStars: Map<BookKey, UserEpisodeStarEntity> = emptyMap(),
    /** Books from ARD Sounds in the library, by their ID at ARD Sounds. */
    val ardBooks: Map<String, UserArdBookEntity> = emptyMap(),
    /** Episodes and books selected individually for the watch. */
    val wearBooks: Map<BookKey, UserWearBookEntity> = emptyMap(),
) {
    val isEmpty: Boolean
        get() = servers.isEmpty() && spokenServers.isEmpty() && podcasts.isEmpty() && listeningStats.isEmpty() &&
            listeningDays.isEmpty() && progress.isEmpty() && playlists.isEmpty() && favorites.isEmpty() &&
            playEvents.isEmpty() && wearSelections.isEmpty() && episodeDownloads.isEmpty() && collectionFavorites.isEmpty() &&
            bookmarks.isEmpty() && episodeStars.isEmpty() && ardBooks.isEmpty() && wearBooks.isEmpty()

    /**
     * Whether there is any server, subscription or statistics entry. Removing the last server keeps the statistics, so a main
     * database without all of them, next to a backup with some, has lost its data rather than been emptied by the user.
     */
    val hasServersFeedsOrStats: Boolean
        get() = servers.isNotEmpty() || spokenServers.isNotEmpty() || podcasts.isNotEmpty() || listeningStats.isNotEmpty() ||
            listeningDays.isNotEmpty()

    /**
     * Whether progress or bookmarks of [serverId] have no place: neither an Audiobookshelf server nor a subscription of this snapshot,
     * nor the device or ARD Sounds (e.g. a trial subscription the cleaner has deleted, review4 T25).
     */
    fun isOrphan(serverId: String): Boolean =
        !hasNoBookServer(serverId) && serverId !in spokenServers && serverId !in podcasts

    /** Entries of the backed-up playlists, in their order. */
    fun entriesOf(playlistKey: String): List<UserSongKey> =
        playlistEntries.values.filter { it.playlistKey == playlistKey }.sortedBy { it.position }.map { it.song }

    /**
     * Playlists whose state ([UserPlaylistEntity.updatedAt]) is unchanged since [saved] keep their backed-up entries:
     * if songs are missing (not back yet or gone from the library), they are not lost this way. Only a
     * change to the playlist replaces the backed-up version.
     */
    fun keepingUnchangedPlaylists(saved: UserDataSnapshot): UserDataSnapshot {
        val unchanged = playlists.filter { (key, playlist) -> saved.playlists[key] == playlist }.keys
        if (unchanged.isEmpty()) return this
        return copy(
            playlistEntries = playlistEntries.filterKeys { it.playlistKey !in unchanged } +
                saved.playlistEntries.filterKeys { it.playlistKey in unchanged },
        )
    }

    /**
     * This snapshot, supplemented by what is only in [other]. Existing rows stay; progress, bookmarks and
     * favorites come from [other] if they are newer there. Playlist entries only together with their playlist.
     */
    fun plusMissing(other: UserDataSnapshot): UserDataSnapshot {
        val addedPlaylists = other.playlists.keys - playlists.keys
        return UserDataSnapshot(
            servers = other.servers + servers,
            spokenServers = other.spokenServers + spokenServers,
            podcasts = other.podcasts + podcasts,
            listeningStats = other.listeningStats + listeningStats,
            listeningDays = other.listeningDays + listeningDays,
            progress = newest(progress, other.progress) { it.updatedAt },
            playlists = other.playlists + playlists,
            playlistEntries = playlistEntries + other.playlistEntries.filterKeys { it.playlistKey in addedPlaylists },
            favorites = newest(favorites, other.favorites) { it.changedAt },
            playEvents = other.playEvents + playEvents,
            wearSelections = other.wearSelections + wearSelections,
            episodeDownloads = other.episodeDownloads + episodeDownloads,
            collectionFavorites = newest(collectionFavorites, other.collectionFavorites) { it.changedAt },
            bookmarks = newest(bookmarks, other.bookmarks) { it.updatedAt },
            episodeStars = other.episodeStars + episodeStars,
            ardBooks = other.ardBooks + ardBooks,
            wearBooks = other.wearBooks + wearBooks,
        )
    }

    /** What changed from [previous] to this snapshot. */
    fun changesSince(previous: UserDataSnapshot): UserDataChanges = UserDataChanges(
        servers = rowChanges(previous.servers, servers),
        spokenServers = rowChanges(previous.spokenServers, spokenServers),
        podcasts = rowChanges(previous.podcasts, podcasts),
        listeningStats = rowChanges(previous.listeningStats, listeningStats),
        listeningDays = rowChanges(previous.listeningDays, listeningDays),
        progress = rowChanges(previous.progress, progress),
        playlists = rowChanges(previous.playlists, playlists),
        playlistEntries = rowChanges(previous.playlistEntries, playlistEntries),
        favorites = rowChanges(previous.favorites, favorites),
        playEvents = rowChanges(previous.playEvents, playEvents),
        wearSelections = rowChanges(previous.wearSelections, wearSelections),
        episodeDownloads = rowChanges(previous.episodeDownloads, episodeDownloads),
        collectionFavorites = rowChanges(previous.collectionFavorites, collectionFavorites),
        bookmarks = rowChanges(previous.bookmarks, bookmarks),
        episodeStars = rowChanges(previous.episodeStars, episodeStars),
        ardBooks = rowChanges(previous.ardBooks, ardBooks),
        wearBooks = rowChanges(previous.wearBooks, wearBooks),
    )

    companion object {
        /** The backed-up rows from `user.db`. */
        fun ofUser(
            servers: List<UserServerEntity>,
            spokenServers: List<UserSpokenServerEntity>,
            podcasts: List<UserPodcastEntity>,
            listeningStats: List<UserListeningStatEntity>,
            listeningDays: List<UserListeningDayEntity>,
            progress: List<UserBookProgressEntity> = emptyList(),
            playlists: List<UserPlaylistEntity> = emptyList(),
            playlistEntries: List<UserPlaylistEntryEntity> = emptyList(),
            favorites: List<UserFavoriteEntity> = emptyList(),
            playEvents: List<UserPlayEventEntity> = emptyList(),
            wearSelections: List<UserWearSelectionEntity> = emptyList(),
            episodeDownloads: List<UserEpisodeDownloadEntity> = emptyList(),
            collectionFavorites: List<UserCollectionFavoriteEntity> = emptyList(),
            bookmarks: List<UserBookmarkEntity> = emptyList(),
            episodeStars: List<UserEpisodeStarEntity> = emptyList(),
            ardBooks: List<UserArdBookEntity> = emptyList(),
            wearBooks: List<UserWearBookEntity> = emptyList(),
        ) = UserDataSnapshot(
            servers = servers.associateBy { it.sourceId },
            spokenServers = spokenServers.associateBy { it.serverId },
            podcasts = podcasts.associateBy { it.feedId },
            listeningStats = listeningStats.associateBy { StatKey(it.source, it.groupKey, it.itemKey) },
            listeningDays = listeningDays.associateBy { DayKey(it.day, it.kind.name, it.groupKey) },
            progress = progress.associateBy { BookKey(it.serverId, it.remoteId) },
            playlists = playlists.associateBy { it.playlistKey },
            playlistEntries = playlistEntries.associateBy { EntryKey(it.playlistKey, it.position) },
            favorites = favorites.associateBy { it.song },
            playEvents = playEvents.associateBy { PlayKey(it.song, it.playedAt) },
            wearSelections = wearSelections.associateBy { it.key },
            episodeDownloads = episodeDownloads.associateBy { BookKey(it.serverId, it.remoteId) },
            collectionFavorites = collectionFavorites.associateBy { it.key },
            bookmarks = bookmarks.associateBy { it.key },
            episodeStars = episodeStars.associateBy { BookKey(it.serverId, it.remoteId) },
            ardBooks = ardBooks.associateBy { it.remoteId },
            wearBooks = wearBooks.associateBy { BookKey(it.serverId, it.remoteId) },
        )

        /**
         * What is backed up from the main database: music servers, ABS servers, subscribed podcasts, statistics,
         * progress (for Audiobookshelf only what has not been reported yet or has its own speed or effects), bookmarks (without deleted ones; for Audiobookshelf
         * only those the server doesn't fully know: not yet reported, not reported in their current state, or with a note,
         * which it doesn't store), local playlists (with `backupKey`), favorites of
         * songs, albums and artists without a server, listening history, the selection for the watch (already keyed, see `UserDataBackup`),
         * downloads of episodes of backed-up subscriptions, saved episodes, the books from ARD Sounds in the library along with their parts
         * ([ardParts], per book) and the episodes and books selected for the watch ([wearBooks]; only those of a backed-up subscription or
         * Audiobookshelf server, from the device or from ARD Sounds: one of a trial subscription would never find its episode again and
         * would hold the watch after a restore).
         */
        fun ofMain(
            sources: List<SourceEntity>,
            spokenServers: List<SpokenServerEntity>,
            feeds: List<PodcastFeedEntity>,
            listeningStats: List<ListeningStatEntity>,
            listeningDays: List<ListeningDayEntity>,
            progress: List<KeyedProgress> = emptyList(),
            playlists: List<PlaylistEntity> = emptyList(),
            playlistEntries: List<KeyedPlaylistEntry> = emptyList(),
            favorites: List<KeyedFavorite> = emptyList(),
            playEvents: List<KeyedPlayEvent> = emptyList(),
            wearSelections: List<UserWearSelectionEntity> = emptyList(),
            episodeDownloads: List<KeyedEpisodeDownload> = emptyList(),
            collectionFavorites: List<KeyedCollectionFavorite> = emptyList(),
            bookmarks: List<KeyedBookmark> = emptyList(),
            episodeStars: List<KeyedStarredEpisode> = emptyList(),
            ardBooks: List<KeyedBook> = emptyList(),
            ardParts: List<KeyedBookPart> = emptyList(),
            wearBooks: List<KeyedWearBook> = emptyList(),
        ) = ofUser(
            servers = sources.filter { it.type.isServer }.map { it.toUser() },
            spokenServers = spokenServers.map { it.toUser() },
            // Podcasts from the device are rebuilt by the scan from the folders.
            podcasts = feeds.filterNot { it.feedId.startsWith(LOCAL_PODCAST_PREFIX) }.map { it.toUser() },
            episodeDownloads = feeds.filterNot { it.feedId.startsWith(LOCAL_PODCAST_PREFIX) }.map { it.feedId }.toSet().let { kept ->
                episodeDownloads.filter { it.serverId in kept }.map { it.toUser() }
            },
            // Like progress, also the episodes from the device (by path); the scan creates them again.
            episodeStars = episodeStars.map { UserEpisodeStarEntity(it.serverId, it.remoteId, it.starredAt) },
            ardBooks = ardParts.groupBy { it.remoteId }.let { parts ->
                ardBooks.map { UserArdBookEntity(it.remoteId, it.addedAt, parts[it.remoteId]?.let(::encodeArdParts)) }
            },
            // Like the starred episodes, also those from the device (the scan creates them again).
            wearBooks = (spokenServers.map { it.serverId } + feeds.map { it.feedId }).toSet().let { kept ->
                wearBooks.filter { hasNoBookServer(it.serverId) || it.serverId in kept }
                    .map { UserWearBookEntity(it.serverId, it.remoteId, it.addedAt) }
            },
            listeningStats = listeningStats.map { it.toUser() },
            listeningDays = listeningDays.map { it.toUser() },
            // Audiobookshelf keeps the position, but not the book's own speed and effects: those stay in the backup after reporting
            // (review5 P28).
            progress = spokenServers.map { it.serverId }.toSet().let { abs ->
                progress.filter { it.serverId !in abs || it.progress.dirty || it.progress.hasOwnPlayback }.map { it.toUser() }
            },
            bookmarks = spokenServers.map { it.serverId }.toSet().let { abs ->
                bookmarks.filter { !it.bookmark.deleted && (it.serverId !in abs || !it.bookmark.knownToServer) }.map { it.toUser() }
            },
            playlists = playlists.mapNotNull { playlist ->
                playlist.backupKey?.takeIf { playlist.boundSourceId == null }?.let { UserPlaylistEntity(it, playlist.name, playlist.updatedAt) }
            },
            playlistEntries = playlists.mapNotNull { p -> p.backupKey?.let { p.playlistId to it } }.toMap().let { keys ->
                playlistEntries.groupBy { it.playlistId }.flatMap { (playlistId, entries) ->
                    val key = keys[playlistId] ?: return@flatMap emptyList()
                    entries.mapIndexed { position, entry -> UserPlaylistEntryEntity(key, position, entry.song.key) }
                }
            },
            favorites = favorites.map { UserFavoriteEntity(it.song.key, it.changedAt) },
            playEvents = playEvents.map { UserPlayEventEntity(it.song.key, it.playedAt, it.listenedMs) },
            wearSelections = wearSelections,
            collectionFavorites = collectionFavorites.map { UserCollectionFavoriteEntity(it.kind, it.artist, it.title, it.changedAt, it.year) },
        )
    }
}

data class StatKey(val source: String, val groupKey: String, val itemKey: String)

data class DayKey(val day: Long, val kind: String, val groupKey: String)

/** A book or an episode, independent of this device's `bookId`. */
data class BookKey(val serverId: String, val remoteId: String)

data class EntryKey(val playlistKey: String, val position: Int)

data class PlayKey(val song: UserSongKey, val playedAt: Long)

/** A bookmark: its book or episode and the position in it. */
data class BookmarkKey(val serverId: String, val remoteId: String, val positionMs: Long)

val UserBookmarkEntity.key: BookmarkKey get() = BookmarkKey(serverId, remoteId, positionMs)

/** The server has it as it is here; it doesn't know the note. */
private val BookmarkEntity.knownToServer: Boolean get() = remoteTime != null && !dirty && note.isEmpty()

/** An album (album artist, title, year; 0 = unknown) or an artist (name, empty title, 0), normalized. */
data class CollectionKey(val kind: FavoriteKind, val artist: String, val title: String, val year: Int = 0)

val UserCollectionFavoriteEntity.key: CollectionKey get() = CollectionKey(kind, artist, title, year)

/** The cross-device key of the song. */
val KeyedSong.key: UserSongKey get() = UserSongKey(albumArtist, album, disc, track, title, year ?: 0)

/** The contents of `user.db`. */
suspend fun UserDataDao.snapshot() = UserDataSnapshot.ofUser(
    servers = servers(),
    spokenServers = spokenServers(),
    podcasts = podcasts(),
    listeningStats = listeningStats(),
    listeningDays = listeningDays(),
    progress = progress(),
    playlists = playlists(),
    playlistEntries = playlistEntries(),
    favorites = favorites(),
    playEvents = playEvents(),
    wearSelections = wearSelections(),
    episodeDownloads = episodeDownloads(),
    collectionFavorites = collectionFavorites(),
    bookmarks = bookmarks(),
    episodeStars = episodeStars(),
    ardBooks = ardBooks(),
    wearBooks = wearBooks(),
)

/** [mine] where it is at least as new as [other], otherwise [other]. */
private fun <K, V> newest(mine: Map<K, V>, other: Map<K, V>, time: (V) -> Long): Map<K, V> =
    other + mine.filter { (key, value) -> other[key]?.let { time(it) <= time(value) } ?: true }

private fun <K, V> rowChanges(old: Map<K, V>, new: Map<K, V>): RowChanges<V> = RowChanges(
    upserts = new.filter { (key, value) -> old[key] != value }.values.toList(),
    deletes = old.filterKeys { it !in new }.values.toList(),
)

fun SourceEntity.toUser() =
    UserServerEntity(sourceId, type, name, baseUrl, username, priority, serverCapabilities, enabled)

fun SpokenServerEntity.toUser() = UserSpokenServerEntity(serverId, name, baseUrl, libraryId, enabled, openId, hiddenLibraries)

fun PodcastFeedEntity.toUser() = UserPodcastEntity(
    feedId, url, title, author, imageUrl, autoDownload, newestFirst, continuous, notifyNew, archived,
    speed, skipSilence, volumeBoostDb, skipIntroS, skipOutroS, episodeFilter, listOldestFirst, queueMode, keepEpisodes,
    autoDownloadCount, keepUpdated,
)

fun ListeningStatEntity.toUser() =
    UserListeningStatEntity(source, groupKey, itemKey, kind, groupTitle, itemTitle, listenedMs, lastListenedAt)

fun ListeningDayEntity.toUser() = UserListeningDayEntity(day, kind, groupKey, listenedMs, savedMs)

/** The book plays with its own speed, "skip silence" or volume instead of those of its section (review5 P28). */
internal val BookProgressEntity.hasOwnPlayback: Boolean
    get() = speed != null || skipSilence != null || volumeBoostDb != null

fun KeyedProgress.toUser() = with(progress) {
    UserBookProgressEntity(serverId, remoteId, positionMs, durationMs, finished, speed, updatedAt, dirty, hidden, skipSilence, volumeBoostDb)
}

fun KeyedBookmark.toUser() = with(bookmark) {
    UserBookmarkEntity(serverId, remoteId, positionMs, title, note, createdAt, updatedAt, remoteTime)
}

/** Back into the main database; the sync state starts over. */
fun UserServerEntity.toMain() = SourceEntity(
    sourceId = sourceId,
    type = type,
    name = name,
    baseUrl = baseUrl,
    username = username,
    priority = priority,
    serverCapabilities = serverCapabilities,
    enabled = enabled,
)

fun UserSpokenServerEntity.toMain() = SpokenServerEntity(
    serverId = serverId,
    name = name,
    baseUrl = baseUrl,
    libraryId = libraryId,
    enabled = enabled,
    openId = openId,
    hiddenLibraries = hiddenLibraries,
)

/**
 * `lastRefreshAt = 0`: the next time the app is opened, the podcast counts as stale and fetches its episodes (a paused one too,
 * since it was never synced here).
 */
fun UserPodcastEntity.toMain() = PodcastFeedEntity(
    feedId = feedId,
    url = url,
    title = title,
    author = author,
    imageUrl = imageUrl,
    autoDownload = autoDownload,
    newestFirst = newestFirst,
    continuous = continuous,
    notifyNew = notifyNew,
    archived = archived,
    speed = speed,
    skipSilence = skipSilence,
    volumeBoostDb = volumeBoostDb,
    skipIntroS = skipIntroS,
    skipOutroS = skipOutroS,
    episodeFilter = episodeFilter,
    listOldestFirst = listOldestFirst,
    queueMode = queueMode,
    keepEpisodes = keepEpisodes,
    autoDownloadCount = autoDownloadCount,
    keepUpdated = keepUpdated,
)

fun UserListeningStatEntity.toMain() = ListeningStatEntity(
    source = source,
    kind = kind,
    groupKey = groupKey,
    groupTitle = groupTitle,
    itemKey = itemKey,
    itemTitle = itemTitle,
    listenedMs = listenedMs,
    lastListenedAt = lastListenedAt,
)

fun UserListeningDayEntity.toMain() = ListeningDayEntity(day, kind, groupKey, listenedMs, savedMs)

/** Downloaded or queued counts as wanted; removed or failed does not. */
fun KeyedEpisodeDownload.toUser() = UserEpisodeDownloadEntity(
    serverId = serverId,
    remoteId = remoteId,
    wanted = downloadState == TrackDownload.QUEUED || downloadState == TrackDownload.RUNNING || downloadState == TrackDownload.DONE,
)

fun UserBookProgressEntity.toMain(bookId: Long) = BookProgressEntity(
    bookId = bookId,
    positionMs = positionMs,
    durationMs = durationMs,
    finished = finished,
    speed = speed,
    updatedAt = updatedAt,
    dirty = dirty,
    hidden = hidden,
    skipSilence = skipSilence,
    volumeBoostDb = volumeBoostDb,
    // The backup doesn't know when it was finished; the last update is the best approximation (like the migration to v43).
    finishedAt = updatedAt.takeIf { finished },
)

/** [dirty]: still to be created on the server (see `UserDataBackup.applyBookmarks`). */
fun UserBookmarkEntity.toMain(bookId: Long, dirty: Boolean) = BookmarkEntity(
    bookId = bookId,
    positionMs = positionMs,
    title = title,
    note = note,
    createdAt = createdAt,
    updatedAt = updatedAt,
    remoteTime = remoteTime,
    dirty = dirty,
)
