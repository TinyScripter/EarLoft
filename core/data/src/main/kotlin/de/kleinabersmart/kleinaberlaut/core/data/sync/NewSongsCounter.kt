package de.kleinabersmart.kleinaberlaut.core.data.sync

import de.kleinabersmart.kleinaberlaut.core.database.dao.LibraryDao
import de.kleinabersmart.kleinaberlaut.core.database.dao.SourceDao
import javax.inject.Inject

/**
 * Zählt die Songs, die ein Abgleich neu angelegt hat: [begin] vor dem Abgleich, [newSongs] danach. Songs bekommen eine
 * fortlaufende `songId`, die nie wiederverwendet wird; neu ist also, was eine größere Id hat als der höchste Song vorher.
 * Neu getaggte Dateien behalten ihre Id und gelten nicht als neu.
 */
class NewSongsCounter @Inject constructor(
    private val libraryDao: LibraryDao,
    private val sourceDao: SourceDao,
) {
    /** [firstImport]: Eine der Quellen wird zum ersten Mal eingelesen, dann ist alles „neu“ und wird nicht gezählt. */
    data class Baseline(val maxSongId: Long, val firstImport: Boolean)

    suspend fun begin(sourceIds: List<String>): Baseline = Baseline(
        maxSongId = libraryDao.maxSongId(),
        firstImport = sourceIds.any { (sourceDao.get(it)?.lastFullSyncAt ?: 0L) == 0L },
    )

    suspend fun newSongs(baseline: Baseline): Int =
        if (baseline.firstImport) 0 else libraryDao.countVisibleSongsAfter(baseline.maxSongId)
}
