package de.kleinabersmart.earloft.core.data.sync

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Songs that took over an older song when the server delivered the same track under a new ID ([MergeResolver.adoptChangedIds]):
 * they have a new `songId`, but are no new music, so [NewSongsCounter] skips them (review5 P23). Only for the running process:
 * the count follows the sync in the same run. `songId`s are never reused, so entries never go stale.
 */
@Singleton
class AdoptedSongs @Inject constructor() {

    private val songIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    fun add(songId: Long) {
        songIds += songId
    }

    operator fun contains(songId: Long): Boolean = songId in songIds
}
