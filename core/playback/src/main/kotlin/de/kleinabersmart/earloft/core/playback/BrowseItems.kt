package de.kleinabersmart.earloft.core.playback

import android.content.Context
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import de.kleinabersmart.earloft.core.data.spoken.BookGroup
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.BookWithProgress
import de.kleinabersmart.earloft.core.model.PodcastFeed
import de.kleinabersmart.earloft.core.model.TrackDownload

/*
 * Browse tree entries for podcasts and books (concept 7.1) and the display hints for Android Auto.
 */

/** Hints at the root: folders and titles as a list, unless a folder says otherwise ([gridChildrenExtras]). */
@OptIn(UnstableApi::class)
internal fun rootExtras(): Bundle = Bundle().apply {
    putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
    putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
}

/** On a folder: Android Auto shows its content as a grid. */
@OptIn(UnstableApi::class)
internal fun gridChildrenExtras(): Bundle = Bundle().apply {
    putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
    putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
}

/**
 * Puts the entry under the heading [title]. Android Auto groups consecutive entries with the same
 * heading together (tab "Recent", search results).
 */
@OptIn(UnstableApi::class)
internal fun MediaItem.inGroup(title: String): MediaItem {
    val extras = Bundle(mediaMetadata.extras ?: Bundle.EMPTY).apply {
        putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, title)
    }
    return buildUpon().setMediaMetadata(mediaMetadata.buildUpon().setExtras(extras).build()).build()
}

/**
 * A book or episode to play. Android Auto shows the progress ([MediaConstants.EXTRAS_KEY_COMPLETION_STATUS])
 * and whether it is downloaded; files from the device don't count as "downloaded", as in the app.
 */
@OptIn(UnstableApi::class)
internal fun BookWithProgress.toPlayableSpokenItem(context: Context, subtitle: String, download: TrackDownload?): MediaItem {
    val progress = progress
    val extras = Bundle().apply {
        when {
            progress == null || (progress.positionMs == 0L && !progress.finished) ->
                putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED)
            progress.finished ->
                putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED)
            else -> {
                putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED)
                putDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE, progress.fraction.toDouble())
            }
        }
        when (download) {
            TrackDownload.DONE -> putLong(MediaConstants.EXTRAS_KEY_DOWNLOAD_STATUS, MediaConstants.EXTRAS_VALUE_STATUS_DOWNLOADED)
            TrackDownload.QUEUED, TrackDownload.RUNNING ->
                putLong(MediaConstants.EXTRAS_KEY_DOWNLOAD_STATUS, MediaConstants.EXTRAS_VALUE_STATUS_DOWNLOADING)
            else -> Unit
        }
    }
    return MediaItem.Builder()
        .setMediaId(MediaIds.spoken(book.id))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(book.title)
                .setArtist(subtitle)
                .setArtworkUri(CoverProvider.bookCoverUri(context, book.id))
                .setDurationMs(book.durationMs.takeIf { it > 0 })
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(if (book.kind == BookKind.EPISODE) MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE else MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                .setExtras(extras)
                .build(),
        )
        .build()
}

internal fun PodcastFeed.toBrowsableMediaItem(context: Context): MediaItem =
    folderMediaItem(
        MediaIds.feed(id),
        title,
        MediaMetadata.MEDIA_TYPE_PODCAST,
        subtitle = author.takeIf { it.isNotBlank() },
        artworkUri = CoverProvider.feedCoverUri(context, id),
    )

/** An author or series with the cover of the first book and the number of books. */
internal fun BookGroup.toBrowsableMediaItem(context: Context, mediaId: String): MediaItem =
    folderMediaItem(
        mediaId,
        name,
        MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS,
        gridChildren = true,
        subtitle = context.resources.getQuantityString(R.plurals.book_count, books.size, books.size),
        artworkUri = books.firstOrNull()?.let { CoverProvider.bookCoverUri(context, it.book.id) },
    )
