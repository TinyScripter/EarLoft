package de.kleinabersmart.earloft.core.data.sync

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Notices to the user when the sync couldn't apply one of their changes (concept 6.4). */
sealed interface SyncNotice {
    /** The playlist was also changed on the server; its version applies, the local change is gone. */
    data class PlaylistReplacedByServer(val playlistName: String) : SyncNotice

    /** The server rejected a change, or it couldn't be sent even after many attempts. */
    data class ChangeDropped(val kind: Kind) : SyncNotice {
        enum class Kind { FAVORITE, PLAYLIST }
    }
}

/**
 * Whoever isn't listening right now (app in the background) misses the notice – it isn't delivered later, because
 * an outdated toast on the next start confuses more than it helps.
 */
@Singleton
class SyncNotices @Inject constructor() {
    private val flow = MutableSharedFlow<SyncNotice>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val notices: SharedFlow<SyncNotice> = flow.asSharedFlow()

    fun post(notice: SyncNotice) {
        flow.tryEmit(notice)
    }
}
