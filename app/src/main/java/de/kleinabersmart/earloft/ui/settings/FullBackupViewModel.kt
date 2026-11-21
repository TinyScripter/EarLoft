package de.kleinabersmart.earloft.ui.settings

import android.content.Context
import android.content.res.Resources
import android.database.SQLException
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.backup.BackupCounts
import de.kleinabersmart.earloft.core.data.backup.FullBackup
import de.kleinabersmart.earloft.core.data.backup.FullBackupException
import de.kleinabersmart.earloft.core.data.backup.FullBackupInterruptedException
import de.kleinabersmart.earloft.core.data.backup.FullBackupResult
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import de.kleinabersmart.earloft.core.data.podcast.PodcastRefresher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

/**
 * Outcome of a full backup or of a failed restore. The result of a successful one is reported by
 * `RestoreNoticeEffect` at the root, together with the missing passwords.
 */
sealed interface FullBackupMessage {
    data class Exported(val counts: BackupCounts) : FullBackupMessage
    data object ExportFailed : FullBackupMessage

    /** [newerVersion]: the backup comes from a newer version of the app; otherwise it isn't a backup of this app. */
    data class ImportFailed(val newerVersion: Boolean) : FullBackupMessage

    /** Reading the file failed before anything was restored (cloud file not available, storage full, …). */
    data object ImportUnreadable : FullBackupMessage

    /** Restoring stopped midway: part of the backup is already in place, restoring again adds the rest. */
    data object ImportInterrupted : FullBackupMessage
}

/** Settings → General → Backup ([FullBackup]). Both run to completion in the app scope, even if the page goes away first. */
@HiltViewModel
class FullBackupViewModel @Inject constructor(
    private val fullBackup: FullBackup,
    private val podcastRefresher: PodcastRefresher,
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {

    private val _messages = Channel<FullBackupMessage>(Channel.BUFFERED)

    /** Result, to be shown once. */
    val messages: Flow<FullBackupMessage> = _messages.receiveAsFlow()

    private var running = false

    fun export(uri: Uri) = run(failed = FullBackupMessage.ExportFailed) {
        val counts = applicationScope.async {
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "wt")?.use { fullBackup.export(it) }
                    ?: throw IOException("File not writable")
            }
        }.await()
        FullBackupMessage.Exported(counts)
    }

    fun import(uri: Uri) = run(failed = FullBackupMessage.ImportUnreadable) {
        applicationScope.async {
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { fullBackup.import(it) }
                    ?: throw IOException("File not readable")
            }.also {
                // New subscriptions fetch their episodes right away, not only the next time the app is opened; only then does their progress apply.
                if (it.added.podcasts > 0) podcastRefresher.refreshAll()
            }
        }.await()
        null
    }

    /** One job at a time; a second tap in the meantime is ignored. [failed]: message on read and write errors. */
    private fun run(failed: FullBackupMessage, block: suspend () -> FullBackupMessage?) {
        if (running) return
        running = true
        viewModelScope.launch {
            val message = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: FullBackupException) {
                Log.w(TAG, "Backup not restored", e)
                FullBackupMessage.ImportFailed(e.newerVersion)
            } catch (e: FullBackupInterruptedException) {
                Log.w(TAG, "Backup restored only in part", e)
                FullBackupMessage.ImportInterrupted
            } catch (e: IOException) {
                Log.w(TAG, "Backup failed", e)
                failed
            } catch (e: SQLException) {
                Log.w(TAG, "Backup failed", e)
                failed
            } finally {
                running = false
            }
            message?.let { _messages.send(it) }
        }
    }

    private companion object {
        const val TAG = "FullBackupViewModel"
    }
}

/** Text for [message] in the snackbar. */
fun fullBackupMessage(resources: Resources, message: FullBackupMessage): String = when (message) {
    is FullBackupMessage.Exported -> resources.getString(R.string.settings_backup_exported, message.counts.summary(resources))
    FullBackupMessage.ExportFailed -> resources.getString(R.string.settings_backup_export_failed)
    is FullBackupMessage.ImportFailed -> resources.getString(
        if (message.newerVersion) R.string.settings_backup_import_newer else R.string.settings_backup_import_failed,
    )
    FullBackupMessage.ImportUnreadable -> resources.getString(R.string.settings_backup_import_unreadable)
    FullBackupMessage.ImportInterrupted -> resources.getString(R.string.settings_backup_import_interrupted)
}

/** Result of a restored full backup, without the hint about missing passwords. */
fun backupRestoredMessage(resources: Resources, result: FullBackupResult): String = buildString {
    append(resources.getString(R.string.settings_backup_imported, result.added.summary(resources)))
    if (result.localSourcesSkipped) append(' ').append(resources.getString(R.string.settings_backup_local_skipped))
}

private fun BackupCounts.summary(resources: Resources): String {
    val servers = musicServers + audiobookServers
    return listOf(
        resources.getQuantityString(R.plurals.settings_server_count, servers, servers),
        resources.getQuantityString(R.plurals.settings_podcast_count, podcasts, podcasts),
        resources.getQuantityString(R.plurals.count_playlists, playlists, playlists),
    ).joinToString(", ")
}
