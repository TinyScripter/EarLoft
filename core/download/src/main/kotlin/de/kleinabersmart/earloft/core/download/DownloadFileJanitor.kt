package de.kleinabersmart.earloft.core.download

import android.util.Log
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import de.kleinabersmart.earloft.core.data.sync.LibraryChangeListener
import de.kleinabersmart.earloft.core.database.dao.DownloadDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * When song sources disappear (server removed, file deleted on the server), the database deletes
 * their downloads via CASCADE – this removes the corresponding files. After a sync,
 * newly enqueued downloads (changed server files) are started.
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
        // Otherwise replaced files with a different extension would be left behind.
        onSongSourcesRemoved()
        // A running worker picks up new entries itself; appended, another job would run after every sync.
        if (downloadDao.countPending() > 0) scheduler.resume()
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
