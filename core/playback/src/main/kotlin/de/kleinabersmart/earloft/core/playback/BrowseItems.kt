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
 * Einträge des Browse-Trees für Podcasts und Bücher (Konzept 7.1) und die Darstellungs-Hinweise für Android Auto.
 */

/** Hinweise an der Wurzel: Ordner und Titel als Liste, wenn ein Ordner nichts anderes sagt ([gridChildrenExtras]). */
@OptIn(UnstableApi::class)
internal fun rootExtras(): Bundle = Bundle().apply {
    putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
    putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
}

/** An einem Ordner: Android Auto zeigt seinen Inhalt als Raster. */
@OptIn(UnstableApi::class)
internal fun gridChildrenExtras(): Bundle = Bundle().apply {
    putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
    putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
}

/**
 * Stellt den Eintrag unter die Überschrift [title]. Android Auto fasst aufeinanderfolgende Einträge mit derselben
 * Überschrift zu einer Gruppe zusammen (Tab „Zuletzt“, Suchtreffer).
 */
@OptIn(UnstableApi::class)
internal fun MediaItem.inGroup(title: String): MediaItem {
    val extras = Bundle(mediaMetadata.extras ?: Bundle.EMPTY).apply {
        putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, title)
    }
    return buildUpon().setMediaMetadata(mediaMetadata.buildUpon().setExtras(extras).build()).build()
}

/**
 * Ein Buch oder eine Folge zum Abspielen. Android Auto zeigt den Fortschritt ([MediaConstants.EXTRAS_KEY_COMPLETION_STATUS])
 * und ob es geladen ist; Dateien vom Gerät gelten wie in der App nicht als „geladen“.
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

/** Ein Autor oder eine Serie mit dem Cover des ersten Buchs und der Zahl der Bücher. */
internal fun BookGroup.toBrowsableMediaItem(context: Context, mediaId: String): MediaItem =
    folderMediaItem(
        mediaId,
        name,
        MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS,
        gridChildren = true,
        subtitle = context.resources.getQuantityString(R.plurals.book_count, books.size, books.size),
        artworkUri = books.firstOrNull()?.let { CoverProvider.bookCoverUri(context, it.book.id) },
    )
