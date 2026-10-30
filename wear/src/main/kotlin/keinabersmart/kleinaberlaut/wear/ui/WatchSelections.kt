package keinabersmart.kleinaberlaut.wear.ui

import keinabersmart.kleinaberlaut.core.data.repository.PlaylistRepository
import keinabersmart.kleinaberlaut.core.database.dao.WearDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/** Die Auswahlen vom Handy mit ihrer Geräte-Playlist und dem Stand der Downloads (Wear-Konzept 7.2). */
@Singleton
class WatchSelections @Inject constructor(wearDao: WearDao, playlistRepository: PlaylistRepository) {

    /** In der Reihenfolge des Handys; Auswahlen ohne Geräte-Playlist (noch nicht übernommen) fehlen. */
    val selections: Flow<List<SelectionItem>> = combine(
        wearDao.observeSelections(),
        playlistRepository.playlists(),
        wearDao.observeSelectionProgress(),
    ) { selections, playlists, progress ->
        val byId = playlists.associateBy { it.id }
        val progressByKey = progress.associateBy { it.key }
        selections.mapNotNull { selection ->
            val playlist = selection.refId?.let { byId[it] } ?: return@mapNotNull null
            val done = progressByKey[selection.key]?.done ?: 0
            SelectionItem(playlist.id, selection.kind, playlist.name, playlist.songCount, downloadedCount = done)
        }
    }
}
