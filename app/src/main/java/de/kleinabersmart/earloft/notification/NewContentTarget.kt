package de.kleinabersmart.earloft.notification

import android.content.Intent
import android.os.Bundle
import de.kleinabersmart.earloft.core.model.SpokenMode

/**
 * Wohin der Tipp auf eine Benachrichtigung über Neues führt: Start im Bereich [mode]; geht es um genau ein Album, einen
 * Podcast oder ein Buch, gleich dessen Seite darüber.
 */
data class NewContentTarget(
    val mode: SpokenMode,
    val albumId: Long? = null,
    val feedId: String? = null,
    val bookId: Long? = null,
) {
    fun toBundle(): Bundle = Bundle().apply {
        putString(KEY_MODE, mode.name)
        albumId?.let { putLong(KEY_ALBUM, it) }
        feedId?.let { putString(KEY_FEED, it) }
        bookId?.let { putLong(KEY_BOOK, it) }
    }

    companion object {
        const val ACTION_OPEN = "de.kleinabersmart.earloft.OPEN_NEW_CONTENT"
        private const val EXTRA_TARGET = "de.kleinabersmart.earloft.NEW_CONTENT_TARGET"
        private const val KEY_MODE = "mode"
        private const val KEY_ALBUM = "albumId"
        private const val KEY_FEED = "feedId"
        private const val KEY_BOOK = "bookId"

        fun Intent.putNewContentTarget(target: NewContentTarget): Intent = putExtra(EXTRA_TARGET, target.toBundle())

        fun from(intent: Intent?): NewContentTarget? =
            if (intent?.action == ACTION_OPEN) from(intent.getBundleExtra(EXTRA_TARGET)) else null

        fun from(bundle: Bundle?): NewContentTarget? {
            if (bundle == null) return null
            val mode = bundle.getString(KEY_MODE)?.let { name -> SpokenMode.entries.firstOrNull { it.name == name } } ?: return null
            return NewContentTarget(
                mode = mode,
                albumId = if (bundle.containsKey(KEY_ALBUM)) bundle.getLong(KEY_ALBUM) else null,
                feedId = bundle.getString(KEY_FEED),
                bookId = if (bundle.containsKey(KEY_BOOK)) bundle.getLong(KEY_BOOK) else null,
            )
        }
    }
}
