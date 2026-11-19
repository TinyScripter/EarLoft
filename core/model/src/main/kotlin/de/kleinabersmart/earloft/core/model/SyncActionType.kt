package de.kleinabersmart.earloft.core.model

/** Offline changes that are transferred to a server via the sync queue. */
enum class SyncActionType { SET_FAVORITE, UPSERT_PLAYLIST, DELETE_PLAYLIST, SCROBBLE, SET_ALBUM_FAVORITE, SET_ARTIST_FAVORITE }
