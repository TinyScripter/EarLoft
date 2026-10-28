package keinabersmart.kleinaberlaut.cast

import android.util.Log
import keinabersmart.kleinaberlaut.core.data.repository.CastMedia
import keinabersmart.kleinaberlaut.core.data.repository.CastMediaResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cast-Adressen der Songs in der Warteschlange. Der [CastMediaItemConverter] von Media3 muss synchron
 * liefern; deshalb wird vorher aufgelöst ([prepare]) – beim Start einer Cast-Sitzung für die ganze
 * Warteschlange, beim Einreihen für die neuen Songs. Nur im Speicher, weil die Adressen Zugangsdaten enthalten.
 */
@Singleton
class CastMediaCache @Inject constructor(
    private val resolver: CastMediaResolver,
) {
    /** `media == null`: Song lässt sich nicht casten (nur auf dem Handy). */
    private class Entry(val media: CastMedia?)

    private val entries = ConcurrentHashMap<Long, Entry>()

    suspend fun prepare(songIds: Collection<Long>) {
        val missing = songIds.filterNot(entries::containsKey).distinct()
        if (missing.isEmpty()) return
        val resolved = resolver.resolve(missing)
        missing.forEach { entries[it] = Entry(resolved[it]) }
    }

    /** Synchron für Media3; was nicht vorbereitet wurde, wird blockierend nachgeladen (sollte selten sein). */
    fun prepareBlocking(songIds: Collection<Long>) {
        if (songIds.all(entries::containsKey)) return
        Log.w(TAG, "Cast-Adressen nicht vorbereitet, lade ${songIds.size} Songs blockierend")
        runBlocking(Dispatchers.IO) { prepare(songIds) }
    }

    fun get(songId: Long): CastMedia? {
        prepareBlocking(listOf(songId))
        return entries[songId]?.media
    }

    fun isCastable(songId: Long): Boolean = get(songId) != null

    /** Zu Beginn jeder Sitzung: Server oder Passwort können sich seit der letzten geändert haben. */
    fun clear() = entries.clear()

    private companion object {
        const val TAG = "CastMediaCache"
    }
}
