package de.kleinabersmart.earloft.ui.settings

import android.content.Context
import android.content.res.Resources
import android.database.SQLException
import android.net.Uri
import android.util.Log
import androidx.compose.material3.SnackbarDuration
import androidx.lifecycle.ViewModel
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
import de.kleinabersmart.earloft.ui.snackbar.SnackbarMessage
import de.kleinabersmart.earloft.ui.snackbar.Snackbars
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
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
    private val snackbars: Snackbars,
) : ViewModel() {

    fun export(uri: Uri) = run(failed = FullBackupMessage.ExportFailed) {
        val counts = withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "wt")?.use { fullBackup.export(it) }
                ?: throw IOException("File not writable")
        }
        FullBackupMessage.Exported(counts)
    }

    fun import(uri: Uri) = run(failed = FullBackupMessage.ImportUnreadable) {
        val result = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { fullBackup.import(it) }
                ?: throw IOException("File not readable")
        }
        // New subscriptions fetch their episodes right away, not only the next time the app is opened; only then does their progress apply.
        if (result.added.podcasts > 0) podcastRefresher.refreshAll()
        null
    }

    /**
     * One job at a time, in the whole app (settings and wizard have their own view model); a second tap in the meantime is ignored.
     * Job and lock belong to the app scope, and the result goes to the app-wide snackbars: leaving the page neither aborts the job
     * nor lets a second one start, and the user still learns whether it worked (review2 J61, J62). [failed]: message on read and
     * write errors.
     */
    private fun run(failed: FullBackupMessage, block: suspend () -> FullBackupMessage?) {
        if (!running.compareAndSet(false, true)) return
        applicationScope.launch {
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
                running.set(false)
            }
            // Failures stay until they are dismissed (indefinite, with the dismiss button); `keep` alone would still expire.
            message?.let {
                val failure = it !is FullBackupMessage.Exported
                val duration = if (failure) SnackbarDuration.Indefinite else SnackbarDuration.Long
                snackbars.show(SnackbarMessage(fullBackupMessage(context.resources, it), duration = duration, keep = failure))
            }
        }
    }

    private companion object {
        const val TAG = "FullBackupViewModel"

        /** Process-wide: the settings and the wizard each have their own view model. */
        val running = AtomicBoolean(false)
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
    return resources.getString(
        R.string.settings_backup_counts,
        resources.getQuantityString(R.plurals.settings_server_count, servers, servers),
        resources.getQuantityString(R.plurals.settings_podcast_count, podcasts, podcasts),
        resources.getQuantityString(R.plurals.count_playlists, playlists, playlists),
    )
}
