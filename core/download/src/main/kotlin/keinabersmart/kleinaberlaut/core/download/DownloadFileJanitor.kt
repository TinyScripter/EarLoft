package keinabersmart.kleinaberlaut.core.download

import android.util.Log
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import keinabersmart.kleinaberlaut.core.data.sync.LibraryCleanupListener
import keinabersmart.kleinaberlaut.core.database.dao.DownloadDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Verschwinden Song-Quellen (Server entfernt, Datei auf dem Server gelöscht), löscht die Datenbank
 * deren Downloads per CASCADE – hier werden die zugehörigen Dateien entfernt.
 */
internal class DownloadFileJanitor @Inject constructor(
    private val downloadDao: DownloadDao,
    private val storage: DownloadStorage,
) : LibraryCleanupListener {

    override suspend fun onSongSourcesRemoved() {
        val downloads = downloadDao.getAll()
        val deleted = withContext(Dispatchers.IO) { storage.deleteOrphans(downloads) }
        if (deleted > 0) Log.i(TAG, "$deleted verwaiste Download-Dateien gelöscht")
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
    fun bindJanitor(janitor: DownloadFileJanitor): LibraryCleanupListener

    @Binds
    fun bindScheduler(scheduler: WorkManagerDownloadScheduler): DownloadScheduler
}
