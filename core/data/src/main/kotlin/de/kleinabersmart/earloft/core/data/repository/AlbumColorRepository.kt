package de.kleinabersmart.earloft.core.data.repository

import android.os.SystemClock
import de.kleinabersmart.earloft.core.database.dao.LibraryDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val COVER_SIZE_PX = 128

/** How long an album without a cover (or with its server unreachable) is not asked again. */
private const val NO_COVER_RETRY_MS = 10 * 60_000L

/** Seed color of an album for the color scheme; computed once from the cover and stored in `albums.seedColor`. */
@Singleton
class AlbumColorRepository @Inject constructor(
    private val libraryDao: LibraryDao,
    private val artwork: ArtworkRepository,
) {
    /** Albums without a cover, with when that was found (`elapsedRealtime`). */
    private val noCover = ConcurrentHashMap<Long, Long>()

    /**
     * The stored color (ARGB), `null` while missing; if missing, it is computed and the flow emits it afterwards. Room emits
     * again on every change of `albums`: only a changed value counts, otherwise an album without a cover would be looked up
     * each time (review2 J26).
     */
    fun seedColor(albumId: Long): Flow<Int?> =
        libraryDao.observeSeedColor(albumId).distinctUntilChanged().onEach { if (it == null) compute(albumId) }

    /** One-off query (e.g. for the widget): stored color or – if necessary – freshly computed. */
    suspend fun seedColorNow(albumId: Long): Int? =
        libraryDao.observeSeedColor(albumId).first() ?: compute(albumId)

    private suspend fun compute(albumId: Long): Int? =
        colorOfCover(albumId)?.also { libraryDao.setSeedColor(albumId, it) }

    /**
     * Computes the missing colors of all visible albums (for the order by cover color), [BATCH] albums per write: each write
     * reloads and re-sorts the album lists, once per album would let the list jump all the time. Albums without a cover are
     * skipped (and retried after [NO_COVER_RETRY_MS]). Runs until done or cancelled.
     */
    suspend fun fillMissing() {
        libraryDao.getAlbumIdsWithoutSeedColor().chunked(BATCH).forEach { chunk ->
            val colors = chunk.mapNotNull { albumId -> colorOfCover(albumId)?.let { albumId to it } }.toMap()
            if (colors.isNotEmpty()) libraryDao.setSeedColors(colors)
        }
    }

    private suspend fun colorOfCover(albumId: Long): Int? {
        val since = noCover[albumId]
        if (since != null && SystemClock.elapsedRealtime() - since < NO_COVER_RETRY_MS) return null
        val bitmap = artwork.albumCover(albumId, COVER_SIZE_PX) ?: run {
            noCover[albumId] = SystemClock.elapsedRealtime()
            return null
        }
        noCover.remove(albumId)
        return withContext(Dispatchers.Default) { seedColorOf(bitmap) }
    }

    private companion object {
        const val BATCH = 20
    }
}
