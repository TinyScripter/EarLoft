package de.kleinabersmart.earloft.core.model

/** Offline-Änderungen, die per Sync-Queue an einen Server übertragen werden. */
enum class SyncActionType { SET_FAVORITE, UPSERT_PLAYLIST, DELETE_PLAYLIST, SCROBBLE, SET_ALBUM_FAVORITE, SET_ARTIST_FAVORITE }
