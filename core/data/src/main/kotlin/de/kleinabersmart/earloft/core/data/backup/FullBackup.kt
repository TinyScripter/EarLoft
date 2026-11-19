package de.kleinabersmart.earloft.core.data.backup

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.database.UserDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of [FullBackup.import]. */
data class FullBackupResult(
    /** Newly created servers, subscriptions and playlists. */
    val added: BackupCounts,
    /** Adopted settings sections (DataStores). */
    val settings: Int,
    /** Sources on the device were on in the backup, but the permission is missing here; they stayed off. */
    val localSourcesSkipped: Boolean,
)

/** The file is not a full backup ([newerVersion] `false`) or comes from a newer version of the app. */
class FullBackupException(val newerVersion: Boolean, message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Full backup as a file (Settings → General → Backup, concept 10 "Security"): the user data as in
 * `user.db` and the settings, **without** passwords, API keys and logins – the `secrets` DataStore is never included.
 * Feed addresses come along unchanged, even if a provider writes a personal key into them (review D34).
 * A ZIP with `earloft-backup.json` (format, timestamp, schema version), `user.db` (own copy; older versions are
 * migrated on import) and `settings.json` ([SettingsBackup]).
 *
 * Importing adds to the user data ([UserDataBackup.import]) and replaces the settings of the included sections.
 */
@Singleton
class FullBackup @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val userData: UserDataBackup,
    private val settings: SettingsBackup,
    private val restoreNotice: RestoreNotice,
) {
    /** Writes the backup to [output] (stays open); returns what it contains. */
    suspend fun export(output: OutputStream): BackupCounts {
        val snapshot = userData.exportSnapshot()
        val settingsJson = settings.read()
        withContext(Dispatchers.IO) {
            withWorkDir { dir ->
                val db = File(dir, USER_DB)
                val database = UserDatabase.openFile(context, db)
                try {
                    // Even an empty backup needs the file.
                    database.openHelper.writableDatabase
                    database.userDataDao().apply(snapshot.changesSince(UserDataSnapshot()))
                } finally {
                    database.close()
                }
                val manifest = JSONObject()
                    .put(FORMAT, CURRENT_FORMAT)
                    .put(CREATED_AT, System.currentTimeMillis())
                    .put(USER_DB_VERSION, UserDatabase.VERSION)
                val zip = ZipOutputStream(output)
                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write(manifest.toString(2).toByteArray())
                zip.putNextEntry(ZipEntry(USER_DB))
                db.inputStream().use { it.copyTo(zip) }
                zip.putNextEntry(ZipEntry(SETTINGS))
                zip.write(settingsJson.toString(2).toByteArray())
                zip.closeEntry()
                zip.finish()
            }
        }
        return BackupCounts(
            musicServers = snapshot.servers.size,
            audiobookServers = snapshot.spokenServers.size,
            podcasts = snapshot.podcasts.size,
            playlists = snapshot.playlists.size,
        )
    }

    /**
     * Reads the backup from [input] and imports it. The result, including missing passwords, also goes to [RestoreNotice].
     * @throws FullBackupException if it is not a full backup or comes from a newer version.
     */
    suspend fun import(input: InputStream): FullBackupResult = withWorkDir { dir ->
        val db = File(dir, USER_DB)
        val (manifest, settingsJson) = withContext(Dispatchers.IO) { unpack(input, db) }
        if (manifest == null || !manifest.has(FORMAT) || !db.exists()) throw FullBackupException(false, "Not a full backup")
        if (manifest.optInt(FORMAT) > CURRENT_FORMAT || manifest.optInt(USER_DB_VERSION) > UserDatabase.VERSION) {
            throw FullBackupException(true, "Backup from a newer version")
        }
        val snapshot = try {
            val database = UserDatabase.openFile(context, db)
            try {
                database.userDataDao().snapshot()
            } finally {
                database.close()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            // Room: unknown enum value with the same schema, so from a newer version.
            throw FullBackupException(true, "user.db with an unknown value", e)
        } catch (e: RuntimeException) {
            // `SQLException`; Room: schema doesn't match, no migration (`IllegalStateException`); otherwise unreadable.
            throw FullBackupException(false, "user.db not readable", e)
        }
        val imported = userData.import(snapshot)
        val restored = settingsJson?.let { settings.write(it, imported.ids) } ?: SettingsRestore(0, localSourcesSkipped = false)
        FullBackupResult(imported.counts, restored.stores, restored.localSourcesSkipped).also { restoreNotice.backupRestored(it) }
    }

    /** Places `user.db` at [db] and returns manifest and settings; unknown entries are skipped. */
    private fun unpack(input: InputStream, db: File): Pair<JSONObject?, JSONObject?> {
        var manifest: JSONObject? = null
        var settingsJson: JSONObject? = null
        val zip = ZipInputStream(input)
        try {
            generateSequence { zip.nextEntry }.forEach { entry ->
                when (entry.name) {
                    MANIFEST -> manifest = JSONObject(zip.readBytes().decodeToString())
                    SETTINGS -> settingsJson = JSONObject(zip.readBytes().decodeToString())
                    USER_DB -> db.outputStream().use { zip.copyTo(it) }
                }
            }
        } catch (e: JSONException) {
            throw FullBackupException(false, "Corrupted full backup", e)
        }
        return manifest to settingsJson
    }

    /** A separate directory per job, removed afterwards. */
    private suspend fun <T> withWorkDir(block: suspend (File) -> T): T {
        val dir = File(context.cacheDir, "full-backup-${UUID.randomUUID()}")
        withContext(Dispatchers.IO) { dir.mkdirs() }
        return try {
            block(dir)
        } finally {
            withContext(Dispatchers.IO) { dir.deleteRecursively() }
        }
    }

    private companion object {
        const val MANIFEST = "earloft-backup.json"
        const val USER_DB = UserDatabase.NAME
        const val SETTINGS = "settings.json"
        const val FORMAT = "format"
        const val CREATED_AT = "createdAt"
        const val USER_DB_VERSION = "userDatabaseVersion"
        const val CURRENT_FORMAT = 1
    }
}
