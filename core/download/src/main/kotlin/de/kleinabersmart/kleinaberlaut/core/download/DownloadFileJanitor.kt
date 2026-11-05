package de.kleinabersmart.kleinaberlaut.core.download

import android.util.Log
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import de.kleinabersmart.kleinaberlaut.core.data.sync.LibraryChangeListener
import de.kleinabersmart.kleinaberlaut.core.database.dao.DownloadDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Verschwinden Song-Quellen (Server entfernt, Datei auf dem Server gelöscht), löscht die Datenbank
 * deren Downloads per CASCADE – hier werden die zugehörigen Dateien entfernt. Nach einem Abgleich
 * werden neu eingereihte Downloads (geänderte Server-Dateien) gestartet.
 */
internal class DownloadFileJanitor @Inject constructor(
    private val downloadDao: DownloadDao,
    private val storage: DownloadStorage,
    private val scheduler: DownloadScheduler,
) : LibraryChangeListener {

    override suspend fun onSongSourcesRemoved() {
        val downloads = downloadDao.getAll()
        val deleted = withContext(Dispatchers.IO) { storage.deleteOrphans(downloads) }
        if (deleted > 0) Log.i(TAG, "$deleted verwaiste Download-Dateien gelöscht")
    }

    override suspend fun onSyncFinished() {
        // Ersetzte Dateien mit anderer Endung bleiben sonst liegen.
        onSongSourcesRemoved()
        if (downloadDao.countPending() > 0) scheduler.enqueue()
    }

    private companion object {
        const val TAG = "DownloadFileJanitor"
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal interface DownloadBindings {
    @Binds
    @IntoSet
    fun bindJanitor(janitor: DownloadFileJanitor): LibraryChangeListener
}
