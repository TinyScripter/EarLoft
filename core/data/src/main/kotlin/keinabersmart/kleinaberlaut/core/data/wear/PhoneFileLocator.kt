package keinabersmart.kleinaberlaut.core.data.wear

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import keinabersmart.kleinaberlaut.core.data.source.MusicDataSources
import keinabersmart.kleinaberlaut.core.database.dao.WearDao
import keinabersmart.kleinaberlaut.core.database.model.WearSourceRow
import keinabersmart.kleinaberlaut.core.datasource.PlaybackLocation
import keinabersmart.kleinaberlaut.core.model.SourceType
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Eine Datei auf dem Handy, bereit zum Senden an die Uhr: [length] Bytes aus [input]. */
class PhoneFile(val length: Long, val input: InputStream)

/**
 * Findet zu einer Quelle die Datei, die das Handy der Uhr im Original schicken kann (Wear-Konzept 8.4):
 * die lokale Datei oder ein fertiger Download, nie ein Stream. Die Uhr nennt irgendeine `sourceUid` des
 * Songs; welche Quelle die Datei liefert, entscheidet das Handy.
 */
@Singleton
class PhoneFileLocator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wearDao: WearDao,
    private val sources: MusicDataSources,
) {
    /** @return `null`, wenn die Quelle unbekannt ist oder das Handy keine Datei zum Song hat. */
    suspend fun open(sourceUid: String): PhoneFile? {
        val songId = wearDao.getSongIdsOfSources(listOf(sourceUid)).firstOrNull()?.songId ?: return null
        val candidates = wearDao.getSources(listOf(songId))
        // Lokal zuerst: Die Datei liegt auf dem Handy, ein Download wäre eine Kopie davon.
        candidates.filter { it.sourceType == SourceType.LOCAL }.forEach { openLocal(it)?.let { file -> return file } }
        candidates.forEach { openDownload(it)?.let { file -> return file } }
        return null
    }

    private suspend fun openLocal(source: WearSourceRow): PhoneFile? {
        val location = sources[source.sourceId]?.resolvePlayback(source.remoteId) as? PlaybackLocation.LocalFile ?: return null
        return try {
            val descriptor = context.contentResolver.openAssetFileDescriptor(location.uri, "r") ?: return null
            val length = descriptor.length.takeIf { it >= 0 } ?: source.sizeBytes ?: run {
                descriptor.close()
                return null
            }
            PhoneFile(length, descriptor.createInputStream())
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "Lokale Datei ${source.uid} nicht lesbar", e)
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "Kein Zugriff auf ${source.uid}", e)
            null
        }
    }

    private fun openDownload(source: WearSourceRow): PhoneFile? {
        val file = source.downloadPath?.let(::File)?.takeIf { it.isFile } ?: return null
        return PhoneFile(file.length(), file.inputStream())
    }

    private companion object {
        const val TAG = "PhoneFileLocator"
    }
}
