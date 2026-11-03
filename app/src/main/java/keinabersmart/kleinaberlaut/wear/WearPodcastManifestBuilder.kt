package keinabersmart.kleinaberlaut.wear

import keinabersmart.kleinaberlaut.core.data.wear.WatchPodcastContent
import keinabersmart.kleinaberlaut.core.wearsync.WearEpisodeDto
import keinabersmart.kleinaberlaut.core.wearsync.WearEpisodeProgressDto
import keinabersmart.kleinaberlaut.core.wearsync.WearPodcastFeedDto
import keinabersmart.kleinaberlaut.core.wearsync.WearPodcastManifest

/** Bildet die Folgen für die Uhr auf das Folgen-Manifest ab (Wear-Konzept 16.1). */
object WearPodcastManifestBuilder {

    fun build(content: WatchPodcastContent): WearPodcastManifest = WearPodcastManifest(
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
                phoneHasFile = episode.phoneHasFile,
                sizeBytes = episode.sizeBytes,
                progress = episode.progress?.let {
                    WearEpisodeProgressDto(it.positionMs, it.durationMs, it.finished, it.speed, it.updatedAt)
                },
            )
        },
    )

    /** Dasselbe Manifest ohne Fortschritt: Unterscheiden sich nur die Fortschritte, wartet das Handy mit dem Senden. */
    fun withoutProgress(manifest: WearPodcastManifest): WearPodcastManifest =
        manifest.copy(episodes = manifest.episodes.map { it.copy(progress = null) })
}
