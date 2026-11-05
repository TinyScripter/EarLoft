package de.kleinabersmart.tinyamp.core.data.backup

import de.kleinabersmart.tinyamp.core.database.dao.KeyedFavorite
import de.kleinabersmart.tinyamp.core.database.dao.KeyedPlayEvent
import de.kleinabersmart.tinyamp.core.database.dao.KeyedPlaylistEntry
import de.kleinabersmart.tinyamp.core.database.dao.KeyedProgress
import de.kleinabersmart.tinyamp.core.database.dao.KeyedSong
import de.kleinabersmart.tinyamp.core.database.dao.RowChanges
import de.kleinabersmart.tinyamp.core.database.dao.UserDataChanges
import de.kleinabersmart.tinyamp.core.database.entity.BookProgressEntity
import de.kleinabersmart.tinyamp.core.database.entity.ListeningDayEntity
import de.kleinabersmart.tinyamp.core.database.entity.ListeningStatEntity
import de.kleinabersmart.tinyamp.core.database.entity.PlaylistEntity
import de.kleinabersmart.tinyamp.core.database.entity.PodcastFeedEntity
import de.kleinabersmart.tinyamp.core.database.entity.SourceEntity
import de.kleinabersmart.tinyamp.core.database.entity.SpokenServerEntity
import de.kleinabersmart.tinyamp.core.database.entity.UserBookProgressEntity
import de.kleinabersmart.tinyamp.core.database.entity.UserFavoriteEntity
import de.kleinabersmart.tinyamp.core.database.entity.UserListeningDayEntity
import de.kleinabersmart.tinyamp.core.database.entity.UserListeningStatEntity
import de.kleinabersmart.tinyamp.core.database.entity.UserPlayEventEntity
import de.kleinabersmart.tinyamp.core.database.entity.UserPlaylistEntity
import de.kleinabersmart.tinyamp.core.database.entity.UserPlaylistEntryEntity
import de.kleinabersmart.tinyamp.core.database.entity.UserPodcastEntity
import de.kleinabersmart.tinyamp.core.database.entity.UserServerEntity
import de.kleinabersmart.tinyamp.core.database.entity.UserSongKey
import de.kleinabersmart.tinyamp.core.database.entity.UserSpokenServerEntity
import de.kleinabersmart.tinyamp.core.database.entity.UserWearSelectionEntity
import de.kleinabersmart.tinyamp.core.model.LOCAL_PODCAST_PREFIX
import de.kleinabersmart.tinyamp.core.model.SourceType

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
) {
    val isEmpty: Boolean
        get() = servers.isEmpty() && spokenServers.isEmpty() && podcasts.isEmpty() && listeningStats.isEmpty() &&
            listeningDays.isEmpty() && progress.isEmpty() && playlists.isEmpty() && favorites.isEmpty() &&
            playEvents.isEmpty() && wearSelections.isEmpty()

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
        )

        /**
         * Was aus der Hauptdatenbank gesichert wird: Musik-Server, ABS-Server, abonnierte Podcasts, die Statistik und der
         * Fortschritt (bei Audiobookshelf nur der noch nicht gemeldete), lokale Playlists (mit `backupKey`), Favoriten von
         * Songs ohne Server, der Hörverlauf und die Auswahl für die Uhr (schon mit Schlüsseln, siehe `UserDataBackup`).
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
        ) = ofUser(
            servers = sources.filter { it.type == SourceType.SUBSONIC }.map { it.toUser() },
            spokenServers = spokenServers.map { it.toUser() },
            // Podcasts vom Gerät baut der Scan aus den Ordnern neu auf.
            podcasts = feeds.filterNot { it.feedId.startsWith(LOCAL_PODCAST_PREFIX) }.map { it.toUser() },
            listeningStats = listeningStats.map { it.toUser() },
            listeningDays = listeningDays.map { it.toUser() },
            progress = spokenServers.map { it.serverId }.toSet().let { abs ->
                progress.filter { it.serverId !in abs || it.progress.dirty }.map { it.toUser() }
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
        )
    }
}

data class StatKey(val source: String, val groupKey: String, val itemKey: String)

data class DayKey(val day: Long, val kind: String, val groupKey: String)

/** Ein Buch oder eine Folge, unabhängig von der `bookId` dieses Geräts. */
data class BookKey(val serverId: String, val remoteId: String)

data class EntryKey(val playlistKey: String, val position: Int)

data class PlayKey(val song: UserSongKey, val playedAt: Long)

/** Der geräteübergreifende Schlüssel des Songs. */
val KeyedSong.key: UserSongKey get() = UserSongKey(albumArtist, album, disc, track, title)

private fun <K, V> rowChanges(old: Map<K, V>, new: Map<K, V>): RowChanges<V> = RowChanges(
    upserts = new.filter { (key, value) -> old[key] != value }.values.toList(),
    deletes = old.filterKeys { it !in new }.values.toList(),
)

fun SourceEntity.toUser() =
    UserServerEntity(sourceId, type, name, baseUrl, username, priority, serverCapabilities, enabled, castBaseUrl)

fun SpokenServerEntity.toUser() = UserSpokenServerEntity(serverId, name, baseUrl, libraryId, enabled)

fun PodcastFeedEntity.toUser() =
    UserPodcastEntity(feedId, url, title, author, imageUrl, autoDownload, newestFirst, continuous, notifyNew, archived)

fun ListeningStatEntity.toUser() =
    UserListeningStatEntity(source, groupKey, itemKey, kind, groupTitle, itemTitle, listenedMs, lastListenedAt)

fun ListeningDayEntity.toUser() = UserListeningDayEntity(day, kind, groupKey, listenedMs)

fun KeyedProgress.toUser() = with(progress) {
    UserBookProgressEntity(serverId, remoteId, positionMs, durationMs, finished, speed, updatedAt, dirty)
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
    castBaseUrl = castBaseUrl,
)

fun UserSpokenServerEntity.toMain() = SpokenServerEntity(
    serverId = serverId,
    name = name,
    baseUrl = baseUrl,
    libraryId = libraryId,
    enabled = enabled,
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

fun UserListeningDayEntity.toMain() = ListeningDayEntity(day, kind, groupKey, listenedMs)

fun UserBookProgressEntity.toMain(bookId: Long) = BookProgressEntity(
    bookId = bookId,
    positionMs = positionMs,
    durationMs = durationMs,
    finished = finished,
    speed = speed,
    updatedAt = updatedAt,
    dirty = dirty,
)
