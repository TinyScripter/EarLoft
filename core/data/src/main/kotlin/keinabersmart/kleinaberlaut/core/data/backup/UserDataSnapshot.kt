package keinabersmart.kleinaberlaut.core.data.backup

import keinabersmart.kleinaberlaut.core.database.dao.RowChanges
import keinabersmart.kleinaberlaut.core.database.dao.UserDataChanges
import keinabersmart.kleinaberlaut.core.database.entity.ListeningDayEntity
import keinabersmart.kleinaberlaut.core.database.entity.ListeningStatEntity
import keinabersmart.kleinaberlaut.core.database.entity.PodcastFeedEntity
import keinabersmart.kleinaberlaut.core.database.entity.SourceEntity
import keinabersmart.kleinaberlaut.core.database.entity.SpokenServerEntity
import keinabersmart.kleinaberlaut.core.database.entity.UserListeningDayEntity
import keinabersmart.kleinaberlaut.core.database.entity.UserListeningStatEntity
import keinabersmart.kleinaberlaut.core.database.entity.UserPodcastEntity
import keinabersmart.kleinaberlaut.core.database.entity.UserServerEntity
import keinabersmart.kleinaberlaut.core.database.entity.UserSpokenServerEntity
import keinabersmart.kleinaberlaut.core.model.LOCAL_PODCAST_PREFIX
import keinabersmart.kleinaberlaut.core.model.SourceType

/** Der Inhalt von `user.db` (bzw. was hineingehört), je Tabelle nach ihrem stabilen Schlüssel. */
data class UserDataSnapshot(
    val servers: Map<String, UserServerEntity> = emptyMap(),
    val spokenServers: Map<String, UserSpokenServerEntity> = emptyMap(),
    val podcasts: Map<String, UserPodcastEntity> = emptyMap(),
    val listeningStats: Map<StatKey, UserListeningStatEntity> = emptyMap(),
    val listeningDays: Map<DayKey, UserListeningDayEntity> = emptyMap(),
) {
    val isEmpty: Boolean
        get() = servers.isEmpty() && spokenServers.isEmpty() && podcasts.isEmpty() && listeningStats.isEmpty() && listeningDays.isEmpty()

    /** Was sich von [previous] zu diesem Stand geändert hat. */
    fun changesSince(previous: UserDataSnapshot): UserDataChanges = UserDataChanges(
        servers = rowChanges(previous.servers, servers),
        spokenServers = rowChanges(previous.spokenServers, spokenServers),
        podcasts = rowChanges(previous.podcasts, podcasts),
        listeningStats = rowChanges(previous.listeningStats, listeningStats),
        listeningDays = rowChanges(previous.listeningDays, listeningDays),
    )

    companion object {
        /** Die gesicherten Zeilen aus `user.db`. */
        fun ofUser(
            servers: List<UserServerEntity>,
            spokenServers: List<UserSpokenServerEntity>,
            podcasts: List<UserPodcastEntity>,
            listeningStats: List<UserListeningStatEntity>,
            listeningDays: List<UserListeningDayEntity>,
        ) = UserDataSnapshot(
            servers = servers.associateBy { it.sourceId },
            spokenServers = spokenServers.associateBy { it.serverId },
            podcasts = podcasts.associateBy { it.feedId },
            listeningStats = listeningStats.associateBy { StatKey(it.source, it.groupKey, it.itemKey) },
            listeningDays = listeningDays.associateBy { DayKey(it.day, it.kind.name, it.groupKey) },
        )

        /** Was aus der Hauptdatenbank gesichert wird: Musik-Server, ABS-Server, abonnierte Podcasts und die Statistik. */
        fun ofMain(
            sources: List<SourceEntity>,
            spokenServers: List<SpokenServerEntity>,
            feeds: List<PodcastFeedEntity>,
            listeningStats: List<ListeningStatEntity>,
            listeningDays: List<ListeningDayEntity>,
        ) = ofUser(
            servers = sources.filter { it.type == SourceType.SUBSONIC }.map { it.toUser() },
            spokenServers = spokenServers.map { it.toUser() },
            // Podcasts vom Gerät baut der Scan aus den Ordnern neu auf.
            podcasts = feeds.filterNot { it.feedId.startsWith(LOCAL_PODCAST_PREFIX) }.map { it.toUser() },
            listeningStats = listeningStats.map { it.toUser() },
            listeningDays = listeningDays.map { it.toUser() },
        )
    }
}

data class StatKey(val source: String, val groupKey: String, val itemKey: String)

data class DayKey(val day: Long, val kind: String, val groupKey: String)

private fun <K, V> rowChanges(old: Map<K, V>, new: Map<K, V>): RowChanges<V> = RowChanges(
    upserts = new.filter { (key, value) -> old[key] != value }.values.toList(),
    deletes = old.filterKeys { it !in new }.values.toList(),
)

fun SourceEntity.toUser() = UserServerEntity(sourceId, type, name, baseUrl, username, priority, serverCapabilities, enabled)

fun SpokenServerEntity.toUser() = UserSpokenServerEntity(serverId, name, baseUrl, libraryId, enabled)

fun PodcastFeedEntity.toUser() =
    UserPodcastEntity(feedId, url, title, author, imageUrl, autoDownload, newestFirst, continuous, notifyNew)

fun ListeningStatEntity.toUser() =
    UserListeningStatEntity(source, groupKey, itemKey, kind, groupTitle, itemTitle, listenedMs, lastListenedAt)

fun ListeningDayEntity.toUser() = UserListeningDayEntity(day, kind, groupKey, listenedMs)

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
