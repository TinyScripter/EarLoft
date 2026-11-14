package de.kleinabersmart.earloft.core.data.backup

import de.kleinabersmart.earloft.core.database.dao.KeyedBookmark
import de.kleinabersmart.earloft.core.database.dao.KeyedCollectionFavorite
import de.kleinabersmart.earloft.core.database.dao.KeyedEpisodeDownload
import de.kleinabersmart.earloft.core.database.dao.KeyedFavorite
import de.kleinabersmart.earloft.core.database.dao.KeyedPlayEvent
import de.kleinabersmart.earloft.core.database.dao.KeyedPlaylistEntry
import de.kleinabersmart.earloft.core.database.dao.KeyedProgress
import de.kleinabersmart.earloft.core.database.dao.KeyedSong
import de.kleinabersmart.earloft.core.database.dao.KeyedStarredEpisode
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
import de.kleinabersmart.earloft.core.database.entity.UserWearSelectionEntity
import de.kleinabersmart.earloft.core.model.FavoriteKind
import de.kleinabersmart.earloft.core.model.LOCAL_PODCAST_PREFIX
import de.kleinabersmart.earloft.core.model.TrackDownload

/** Der Inhalt von `user.db` (bzw. was hineingehört), je Tabelle nach ihrem stabilen Schlüssel. */
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
) {
    val isEmpty: Boolean
        get() = servers.isEmpty() && spokenServers.isEmpty() && podcasts.isEmpty() && listeningStats.isEmpty() &&
            listeningDays.isEmpty() && progress.isEmpty() && playlists.isEmpty() && favorites.isEmpty() &&
            playEvents.isEmpty() && wearSelections.isEmpty() && episodeDownloads.isEmpty() && collectionFavorites.isEmpty() &&
            bookmarks.isEmpty() && episodeStars.isEmpty()

    /** Einträge der gesicherten Playlists, in ihrer Reihenfolge. */
    fun entriesOf(playlistKey: String): List<UserSongKey> =
        playlistEntries.values.filter { it.playlistKey == playlistKey }.sortedBy { it.position }.map { it.song }

    /**
     * Playlists, deren Stand ([UserPlaylistEntity.updatedAt]) seit [saved] gleich ist, behalten ihre gesicherten Einträge:
     * Fehlen Songs (noch nicht wieder da oder aus der Bibliothek verschwunden), gehen sie so nicht verloren. Erst eine
     * Änderung der Playlist ersetzt die gesicherte Fassung.
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
     * Dieser Stand, ergänzt um das, was nur in [other] steht. Vorhandene Zeilen bleiben; Fortschritt, Lesezeichen und
     * Favoriten kommen aus [other], wenn sie dort neuer sind. Einträge von Playlists nur zusammen mit ihrer Playlist.
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
        )
    }

    /** Was sich von [previous] zu diesem Stand geändert hat. */
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
    )

    companion object {
        /** Die gesicherten Zeilen aus `user.db`. */
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
        )

        /**
         * Was aus der Hauptdatenbank gesichert wird: Musik-Server, ABS-Server, abonnierte Podcasts, die Statistik, der
         * Fortschritt (bei Audiobookshelf nur der noch nicht gemeldete), die Lesezeichen (ohne gelöschte; bei Audiobookshelf
         * nur die, die der Server nicht vollständig kennt: noch nicht oder nicht im jetzigen Stand gemeldet oder mit Notiz,
         * die er nicht speichert), lokale Playlists (mit `backupKey`), Favoriten von
         * Songs, Alben und Künstlern ohne Server, der Hörverlauf, die Auswahl für die Uhr (schon mit Schlüsseln, siehe `UserDataBackup`), die
         * Downloads der Folgen gesicherter Abos und die gemerkten Folgen.
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
        ) = ofUser(
            servers = sources.filter { it.type.isServer }.map { it.toUser() },
            spokenServers = spokenServers.map { it.toUser() },
            // Podcasts vom Gerät baut der Scan aus den Ordnern neu auf.
            podcasts = feeds.filterNot { it.feedId.startsWith(LOCAL_PODCAST_PREFIX) }.map { it.toUser() },
            episodeDownloads = feeds.filterNot { it.feedId.startsWith(LOCAL_PODCAST_PREFIX) }.map { it.feedId }.toSet().let { kept ->
                episodeDownloads.filter { it.serverId in kept }.map { it.toUser() }
            },
            // Wie der Fortschritt auch die Folgen vom Gerät (über den Pfad); der Scan legt sie wieder an.
            episodeStars = episodeStars.map { UserEpisodeStarEntity(it.serverId, it.remoteId, it.starredAt) },
            listeningStats = listeningStats.map { it.toUser() },
            listeningDays = listeningDays.map { it.toUser() },
            progress = spokenServers.map { it.serverId }.toSet().let { abs ->
                progress.filter { it.serverId !in abs || it.progress.dirty }.map { it.toUser() }
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
            collectionFavorites = collectionFavorites.map { UserCollectionFavoriteEntity(it.kind, it.artist, it.title, it.changedAt) },
        )
    }
}

data class StatKey(val source: String, val groupKey: String, val itemKey: String)

data class DayKey(val day: Long, val kind: String, val groupKey: String)

/** Ein Buch oder eine Folge, unabhängig von der `bookId` dieses Geräts. */
data class BookKey(val serverId: String, val remoteId: String)

data class EntryKey(val playlistKey: String, val position: Int)

data class PlayKey(val song: UserSongKey, val playedAt: Long)

/** Ein Lesezeichen: sein Buch bzw. seine Folge und die Stelle darin. */
data class BookmarkKey(val serverId: String, val remoteId: String, val positionMs: Long)

val UserBookmarkEntity.key: BookmarkKey get() = BookmarkKey(serverId, remoteId, positionMs)

/** Der Server hat es so, wie es hier steht; die Notiz kennt er nicht. */
private val BookmarkEntity.knownToServer: Boolean get() = remoteTime != null && !dirty && note.isEmpty()

/** Ein Album (Album-Künstler, Titel) oder ein Künstler (Name, leerer Titel), normalisiert. */
data class CollectionKey(val kind: FavoriteKind, val artist: String, val title: String)

val UserCollectionFavoriteEntity.key: CollectionKey get() = CollectionKey(kind, artist, title)

/** Der geräteübergreifende Schlüssel des Songs. */
val KeyedSong.key: UserSongKey get() = UserSongKey(albumArtist, album, disc, track, title)

/** Der Inhalt von `user.db`. */
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
)

/** [mine], wo es mindestens so neu ist wie [other], sonst [other]. */
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
    autoDownloadCount,
)

fun ListeningStatEntity.toUser() =
    UserListeningStatEntity(source, groupKey, itemKey, kind, groupTitle, itemTitle, listenedMs, lastListenedAt)

fun ListeningDayEntity.toUser() = UserListeningDayEntity(day, kind, groupKey, listenedMs, savedMs)

fun KeyedProgress.toUser() = with(progress) {
    UserBookProgressEntity(serverId, remoteId, positionMs, durationMs, finished, speed, updatedAt, dirty, hidden, skipSilence, volumeBoostDb)
}

fun KeyedBookmark.toUser() = with(bookmark) {
    UserBookmarkEntity(serverId, remoteId, positionMs, title, note, createdAt, updatedAt, remoteTime)
}

/** Zurück in die Hauptdatenbank; der Abgleichsstand beginnt von vorn. */
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

/** `lastRefreshAt = 0`: Beim nächsten Öffnen der App gilt der Podcast als veraltet und holt seine Folgen. */
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

/** Geladen oder wartend zählt als gewollt; entfernt oder fehlgeschlagen nicht. */
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
)

/** [dirty]: noch beim Server anzulegen (siehe `UserDataBackup.applyBookmarks`). */
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
