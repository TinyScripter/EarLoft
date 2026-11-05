package keinabersmart.kleinaberlaut.wear

import keinabersmart.kleinaberlaut.core.data.wear.WatchSpokenContent
import keinabersmart.kleinaberlaut.core.model.BookChapter
import keinabersmart.kleinaberlaut.core.model.BookProgress
import keinabersmart.kleinaberlaut.core.wearsync.WearBookDto
import keinabersmart.kleinaberlaut.core.wearsync.WearBookTrackDto
import keinabersmart.kleinaberlaut.core.wearsync.WearChapterDto
import keinabersmart.kleinaberlaut.core.wearsync.WearEpisodeDto
import keinabersmart.kleinaberlaut.core.wearsync.WearEpisodeProgressDto
import keinabersmart.kleinaberlaut.core.wearsync.WearPodcastFeedDto
import keinabersmart.kleinaberlaut.core.wearsync.WearPodcastManifest

/** Bildet die Folgen und Bücher für die Uhr auf das Folgen-Manifest ab (Wear-Konzept 16.1/16.2). */
object WearPodcastManifestBuilder {

    fun build(content: WatchSpokenContent): WearPodcastManifest = WearPodcastManifest(
        feeds = content.feeds.map {
            WearPodcastFeedDto(
                feedId = it.feedId,
                url = it.url,
                title = it.title,
                author = it.author,
                imageUrl = it.imageUrl,
                newestFirst = it.newestFirst,
                continuous = it.continuous,
            )
        },
        episodes = content.episodes.map { episode ->
            WearEpisodeDto(
                feedId = episode.feedId,
                guid = episode.guid,
                title = episode.title,
                publishedAt = episode.publishedAt,
                durationMs = episode.durationMs,
                audioUrl = episode.audioUrl,
                mimeType = episode.mimeType,
                coverUrl = episode.coverUrl,
                progress = episode.progress?.toDto(),
                chapters = episode.chapters.toDto(),
                chaptersUrl = episode.chaptersUrl,
            )
        },
        books = content.books.map { book ->
            WearBookDto(
                serverId = book.serverId,
                remoteId = book.remoteId,
                title = book.title,
                author = book.author,
                narrator = book.narrator,
                durationMs = book.durationMs,
                tracks = book.tracks.map { WearBookTrackDto(it.index, it.fileId, it.startMs, it.durationMs, it.mimeType) },
                progress = book.progress?.toDto(),
                chapters = book.chapters.toDto(),
            )
        },
    )

    /** Dasselbe Manifest ohne Fortschritt: Unterscheiden sich nur die Fortschritte, wartet das Handy mit dem Senden. */
    fun withoutProgress(manifest: WearPodcastManifest): WearPodcastManifest =
        manifest.copy(
            episodes = manifest.episodes.map { it.copy(progress = null) },
            books = manifest.books.map { it.copy(progress = null) },
        )

    private fun BookProgress.toDto() = WearEpisodeProgressDto(positionMs, durationMs, finished, speed, updatedAt)

    private fun List<BookChapter>.toDto() = map { WearChapterDto(it.title, it.startMs, it.endMs) }
}
