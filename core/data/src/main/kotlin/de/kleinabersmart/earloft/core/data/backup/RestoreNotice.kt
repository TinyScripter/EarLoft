package de.kleinabersmart.earloft.core.data.backup

import android.content.Context
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.data.source.DataSourceRegistry
import de.kleinabersmart.earloft.core.data.spoken.AudiobookshelfServers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Enabled servers that are missing a password or API key after restoring. */
data class MissingCredentials(val musicServers: Int, val audiobookServers: Int) {
    val total: Int get() = musicServers + audiobookServers
}

/** What needs to be reported after restoring: [backup] after a full backup (otherwise a device switch). */
data class RestoreReport(val missing: MissingCredentials, val backup: FullBackupResult? = null)

/**
 * The notice after restoring (review K9): servers come back without a password or API key, which only exist
 * encrypted on the old device and don't belong in the full backup either. Restoring after a
 * device switch runs at process start, often before the UI is up; that's why a file in `noBackupFilesDir` keeps the
 * notice pending until the UI has shown it once. After a full backup it belongs to the result message.
 */
@Singleton
class RestoreNotice @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val registry: Lazy<DataSourceRegistry>,
    private val audiobookshelf: Lazy<AudiobookshelfServers>,
) {
    private val marker = File(context.noBackupFilesDir, MARKER)
    private val restored = MutableStateFlow(0)
    private val backups = Channel<FullBackupResult>(Channel.BUFFERED)

    /**
     * Once per restore, as soon as the UI is listening. After a device switch only if something is still missing (if the
     * user has already re-entered everything, nothing appears); after a full backup always, with its result.
     */
    val reports: Flow<RestoreReport> = merge(
        restored.mapNotNull { take() }.map { RestoreReport(it) },
        backups.receiveAsFlow().map { RestoreReport(missing(), it) },
    )

    /** Call after restoring servers during a device switch. */
    internal suspend fun serversRestored() {
        withContext(Dispatchers.IO) { marker.createNewFile() }
        restored.value++
    }

    /** After importing a full backup. */
    internal suspend fun backupRestored(result: FullBackupResult) = backups.send(result)

    private suspend fun take(): MissingCredentials? {
        if (!withContext(Dispatchers.IO) { marker.delete() }) return null
        return missing().takeIf { it.total > 0 }
    }

    private suspend fun missing() = MissingCredentials(
        musicServers = registry.get().withoutPassword().size,
        audiobookServers = audiobookshelf.get().withoutKey().size,
    )

    private companion object {
        const val MARKER = "restore_notice_pending"
    }
}
