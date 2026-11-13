package de.kleinabersmart.earloft.wear

import de.kleinabersmart.earloft.core.data.wear.WatchSpokenContent
import de.kleinabersmart.earloft.core.model.BookChapter
import de.kleinabersmart.earloft.core.model.BookProgress
import de.kleinabersmart.earloft.core.wearsync.WearBookDto
import de.kleinabersmart.earloft.core.wearsync.WearBookTrackDto
import de.kleinabersmart.earloft.core.wearsync.WearChapterDto
import de.kleinabersmart.earloft.core.wearsync.WearEpisodeDto
import de.kleinabersmart.earloft.core.wearsync.WearEpisodeProgressDto
import de.kleinabersmart.earloft.core.wearsync.WearPodcastFeedDto
import de.kleinabersmart.earloft.core.wearsync.WearPodcastManifest

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
                speed = it.effects.speed,
                skipSilence = it.effects.skipSilence,
                volumeBoostDb = it.effects.volumeBoostDb,
                skipIntroS = it.skipIntroS,
                skipOutroS = it.skipOutroS,
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

    private fun BookProgress.toDto() = WearEpisodeProgressDto(positionMs, durationMs, finished, speed, updatedAt, skipSilence, volumeBoostDb)

    private fun List<BookChapter>.toDto() = map { WearChapterDto(it.title, it.startMs, it.endMs) }
}
