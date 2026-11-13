package de.kleinabersmart.earloft.core.data.repository

import de.kleinabersmart.earloft.core.data.sync.replaceAction
import de.kleinabersmart.earloft.core.database.dao.CollectionStarState
import de.kleinabersmart.earloft.core.database.dao.SyncDao
import de.kleinabersmart.earloft.core.model.FavoriteKind
import de.kleinabersmart.earloft.core.model.SyncActionType

/*
 * Favoriten von Alben und Künstlern ([FavoriteKind.ALBUM], [FavoriteKind.ARTIST]) nach denselben Regeln wie bei Songs
 * (Konzept 6.4), nur mit eigenen Tabellen (`album_sources`, `artist_sources`). `targetKey` der Sync-Aktion ist die
 * `remoteId` auf dem Server der Aktion. Alles innerhalb einer Transaktion aufrufen.
 */

internal val FavoriteKind.actionType: SyncActionType
    get() = when (this) {
        FavoriteKind.SONG -> SyncActionType.SET_FAVORITE
        FavoriteKind.ALBUM -> SyncActionType.SET_ALBUM_FAVORITE
        FavoriteKind.ARTIST -> SyncActionType.SET_ARTIST_FAVORITE
    }

internal suspend fun SyncDao.setCollectionFavorite(kind: FavoriteKind, id: Long, favorite: Boolean, changedAt: Long) =
    when (kind) {
        FavoriteKind.ALBUM -> setAlbumFavorite(id, favorite, changedAt)
        FavoriteKind.ARTIST -> setArtistFavorite(id, favorite, changedAt)
        FavoriteKind.SONG -> setSongFavorite(id, favorite, changedAt)
    }

internal suspend fun SyncDao.isCollectionFavorite(kind: FavoriteKind, id: Long): Boolean? = when (kind) {
    FavoriteKind.ALBUM -> isAlbumFavorite(id)
    FavoriteKind.ARTIST -> isArtistFavorite(id)
    FavoriteKind.SONG -> isSongFavorite(id)
}

private suspend fun SyncDao.collectionServerSources(kind: FavoriteKind, id: Long): List<CollectionStarState> = when (kind) {
    FavoriteKind.ALBUM -> getAlbumServerSources(id)
    FavoriteKind.ARTIST -> getArtistServerSources(id)
    FavoriteKind.SONG -> error("Songs über pushFavoriteToServers")
}

internal suspend fun SyncDao.collectionStarState(kind: FavoriteKind, sourceId: String, remoteId: String): CollectionStarState? =
    when (kind) {
        FavoriteKind.ALBUM -> getAlbumStarState(sourceId, remoteId)
        FavoriteKind.ARTIST -> getArtistStarState(sourceId, remoteId)
        FavoriteKind.SONG -> error("Songs über getSongSource")
    }

private suspend fun SyncDao.collectionStarStates(kind: FavoriteKind, sourceId: String): List<CollectionStarState> = when (kind) {
    FavoriteKind.ALBUM -> getAlbumStarStates(sourceId)
    FavoriteKind.ARTIST -> getArtistStarStates(sourceId)
    FavoriteKind.SONG -> error("Songs über getStarStates")
}

internal suspend fun SyncDao.setCollectionStarredAt(kind: FavoriteKind, sourceId: String, remoteId: String, starredAt: Long?) =
    when (kind) {
        FavoriteKind.ALBUM -> setAlbumStarredAt(sourceId, remoteId, starredAt)
        FavoriteKind.ARTIST -> setArtistStarredAt(sourceId, remoteId, starredAt)
        FavoriteKind.SONG -> error("Songs über setStarredAt")
    }

private suspend fun SyncDao.hasPendingCollectionFavorite(kind: FavoriteKind, id: Long): Boolean = when (kind) {
    FavoriteKind.ALBUM -> hasPendingAlbumFavorite(id)
    FavoriteKind.ARTIST -> hasPendingArtistFavorite(id)
    FavoriteKind.SONG -> hasPendingFavorite(id)
}

/**
 * Wie [pushFavoriteToServers]: bringt alle Server-Quellen des Albums bzw. Künstlers [id] auf [favorite].
 * @return `true`, wenn danach Aktionen ausstehen.
 */
internal suspend fun SyncDao.pushCollectionFavoriteToServers(
    kind: FavoriteKind,
    id: Long,
    favorite: Boolean,
    exceptSourceId: String? = null,
    exceptRemoteId: String? = null,
): Boolean {
    var pending = false
    for (source in collectionServerSources(kind, id)) {
        if (source.sourceId == exceptSourceId && source.remoteId == exceptRemoteId) continue
        deleteActions(source.sourceId, kind.actionType, source.remoteId)
        if ((source.starredAt != null) != favorite) {
            replaceAction(source.sourceId, kind.actionType, source.remoteId, favorite.toString())
            pending = true
        }
    }
    return pending
}

/**
 * Wie `MergeResolver.mergeFavorite`: Ein Server, der (ent)sternt hat, setzt den Favoriten, die anderen Server folgen;
 * eine neue Server-Quelle für einen schon favorisierten Eintrag bekommt den Stern. Ungesendete lokale Änderungen gewinnen
 * und gehen auch an neue Quellen.
 */
internal suspend fun SyncDao.mergeCollectionFavorite(
    kind: FavoriteKind,
    id: Long,
    sourceId: String,
    remoteId: String,
    starredAt: Long?,
    previousStarredAt: Long? = null,
    isNewSource: Boolean = false,
) {
    val isFavorite = isCollectionFavorite(kind, id) ?: return
    if (hasPendingCollectionFavorite(kind, id)) {
        // Der lokale Stand gilt; eine neue Quelle (z. B. neue ID auf dem Server) soll ihn auch bekommen.
        if (isNewSource) pushCollectionFavoriteToServers(kind, id, isFavorite)
        return
    }
    val starred = starredAt != null
    val serverChanged = if (isNewSource) starred else starred != (previousStarredAt != null)
    when {
        serverChanged && starred != isFavorite -> {
            setCollectionFavorite(kind, id, starred, starredAt?.takeIf { it > 0 } ?: System.currentTimeMillis())
            pushCollectionFavoriteToServers(kind, id, starred, exceptSourceId = sourceId, exceptRemoteId = remoteId)
        }
        isNewSource && isFavorite && !starred -> pushCollectionFavoriteToServers(kind, id, favorite = true)
    }
}

/**
 * Übernimmt den Stand der Alben bzw. Künstler eines Servers ([starred]: `remoteId` → Zeitpunkt). Was er nicht nennt,
 * gilt als entsternt; wie bei Songs zählt nur, was sich gegenüber dem zuletzt bekannten Stand geändert hat.
 */
internal suspend fun SyncDao.mergeCollectionStars(kind: FavoriteKind, sourceId: String, starred: Map<String, Long>) {
    for (state in collectionStarStates(kind, sourceId)) {
        val starredAt = starred[state.remoteId]
        if ((state.starredAt != null) == (starredAt != null)) continue
        setCollectionStarredAt(kind, sourceId, state.remoteId, starredAt)
        mergeCollectionFavorite(kind, state.id, sourceId, state.remoteId, starredAt, state.starredAt)
    }
}
