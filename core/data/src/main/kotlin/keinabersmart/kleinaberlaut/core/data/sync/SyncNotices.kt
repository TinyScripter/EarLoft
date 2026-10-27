package keinabersmart.kleinaberlaut.core.data.sync

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Hinweise an den Nutzer, wenn der Abgleich eine seiner Änderungen nicht umsetzen konnte (Konzept 6.4). */
sealed interface SyncNotice {
    /** Die Playlist wurde auch auf dem Server geändert; deren Fassung gilt, die lokale Änderung ist weg. */
    data class PlaylistReplacedByServer(val playlistName: String) : SyncNotice

    /** Der Server hat eine Änderung abgelehnt bzw. sie ließ sich auch nach vielen Versuchen nicht senden. */
    data class ChangeDropped(val kind: Kind) : SyncNotice {
        enum class Kind { FAVORITE, PLAYLIST }
    }
}

/**
 * Wer gerade nicht zuhört (App im Hintergrund), verpasst den Hinweis – er wird nicht nachgereicht, weil
 * ein veralteter Toast beim nächsten Start mehr verwirrt als hilft.
 */
@Singleton
class SyncNotices @Inject constructor() {
    private val flow = MutableSharedFlow<SyncNotice>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val notices: SharedFlow<SyncNotice> = flow.asSharedFlow()

    fun post(notice: SyncNotice) {
        flow.tryEmit(notice)
    }
}
