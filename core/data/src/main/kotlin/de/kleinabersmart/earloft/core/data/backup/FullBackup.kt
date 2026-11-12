package de.kleinabersmart.earloft.core.data.backup

import android.content.Context
import android.database.SQLException
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.database.UserDatabase
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

/** Ausgang von [FullBackup.import]. */
data class FullBackupResult(
    /** Neu angelegte Server, Abos und Playlists. */
    val added: BackupCounts,
    /** Übernommene Einstellungs-Bereiche (DataStores). */
    val settings: Int,
    /** Quellen auf dem Gerät waren in der Sicherung an, hier fehlt die Berechtigung; sie blieben aus. */
    val localSourcesSkipped: Boolean,
)

/** Die Datei ist keine Komplett-Sicherung ([newerVersion] `false`) oder stammt aus einer neueren Version der App. */
class FullBackupException(val newerVersion: Boolean, message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Komplett-Sicherung als Datei (Einstellungen → Allgemein → Sicherung, Konzept 10 „Sicherheit“): die Nutzerdaten wie in
 * `user.db` und die Einstellungen, **ohne** Passwörter, API-Keys und Tokens – der DataStore `secrets` gehört nie dazu.
 * Ein ZIP mit `earloft-backup.json` (Format, Zeitpunkt, Schemaversion), `user.db` (eigene Kopie; ältere Fassungen werden
 * beim Einlesen migriert) und `settings.json` ([SettingsBackup]).
 *
 * Einspielen ergänzt die Nutzerdaten ([UserDataBackup.import]) und ersetzt die Einstellungen der enthaltenen Bereiche.
 */
@Singleton
class FullBackup @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val userData: UserDataBackup,
    private val settings: SettingsBackup,
) {
    /** Schreibt die Sicherung nach [output] (bleibt offen); liefert, was sie enthält. */
    suspend fun export(output: OutputStream): BackupCounts {
        val snapshot = userData.exportSnapshot()
        val settingsJson = settings.read()
        withContext(Dispatchers.IO) {
            withWorkDir { dir ->
                val db = File(dir, USER_DB)
                val database = UserDatabase.openFile(context, db)
                try {
                    // Auch eine leere Sicherung braucht die Datei.
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
     * Liest die Sicherung aus [input] und spielt sie ein.
     * @throws FullBackupException wenn es keine Komplett-Sicherung ist oder sie aus einer neueren Version stammt.
     */
    suspend fun import(input: InputStream): FullBackupResult = withWorkDir { dir ->
        val db = File(dir, USER_DB)
        val (manifest, settingsJson) = withContext(Dispatchers.IO) { unpack(input, db) }
        if (manifest == null || !manifest.has(FORMAT) || !db.exists()) throw FullBackupException(false, "Keine Komplett-Sicherung")
        if (manifest.optInt(FORMAT) > CURRENT_FORMAT || manifest.optInt(USER_DB_VERSION) > UserDatabase.VERSION) {
            throw FullBackupException(true, "Sicherung aus einer neueren Version")
        }
        val snapshot = try {
            val database = UserDatabase.openFile(context, db)
            try {
                database.userDataDao().snapshot()
            } finally {
                database.close()
            }
        } catch (e: SQLException) {
            throw FullBackupException(false, "user.db nicht lesbar", e)
        } catch (e: IllegalStateException) {
            // Room: Schema passt nicht, keine Migration.
            throw FullBackupException(false, "user.db nicht lesbar", e)
        }
        val added = userData.import(snapshot)
        val restored = settingsJson?.let { settings.write(it) } ?: SettingsRestore(0, localSourcesSkipped = false)
        FullBackupResult(added, restored.stores, restored.localSourcesSkipped)
    }

    /** Legt `user.db` nach [db] und liefert Manifest und Einstellungen; Unbekanntes wird übergangen. */
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
            throw FullBackupException(false, "Beschädigte Komplett-Sicherung", e)
        }
        return manifest to settingsJson
    }

    /** Ein eigenes Verzeichnis je Auftrag, danach wieder weg. */
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
