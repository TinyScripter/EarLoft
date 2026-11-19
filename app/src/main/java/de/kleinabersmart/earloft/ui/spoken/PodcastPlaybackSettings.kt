package de.kleinabersmart.earloft.ui.spoken

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.model.EffectOverrides
import de.kleinabersmart.earloft.core.data.podcast.PodcastSettings
import de.kleinabersmart.earloft.core.model.FeedQueueMode
import de.kleinabersmart.earloft.core.model.PodcastFeed
import de.kleinabersmart.earloft.core.model.SpokenEffects
import de.kleinabersmart.earloft.ui.settings.ChoiceDialog
import de.kleinabersmart.earloft.ui.settings.ClickRow
import de.kleinabersmart.earloft.ui.settings.resumeSecondsLabel
import de.kleinabersmart.earloft.ui.settings.speedLabel
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/** Which choice of the subscription settings is currently open. */
private enum class FeedChoice { SPEED, SILENCE, BOOST, INTRO, OUTRO }

/**
 * "Playback" section of the subscription settings (concept 12.4): speed, trim silence and volume boost – each with
 * "Default (…)" = the area's default –, plus skipping intro and outro (per subscription only).
 */
@Composable
internal fun FeedPlaybackRows(
    feed: PodcastFeed,
    area: SpokenEffects,
    onEffects: ((EffectOverrides) -> EffectOverrides) -> Unit,
    onSkipIntroOutro: (introS: Int, outroS: Int) -> Unit,
) {
    var choosing by rememberSaveable { mutableStateOf<FeedChoice?>(null) }
    val effects = feed.effects
    val silenceLabel: @Composable (Boolean) -> String = {
        stringResource(if (it) R.string.playback_on else R.string.playback_off)
    }

    Column {
        ClickRow(
            title = stringResource(R.string.playback_default_speed),
            value = withDefault(effects.speed, area.speed) { speedLabel(it) },
            icon = DesignR.drawable.ic_speed,
            onClick = { choosing = FeedChoice.SPEED },
        )
        ClickRow(
            title = stringResource(R.string.playback_skip_silence),
            value = withDefault(effects.skipSilence, area.skipSilence, silenceLabel),
            icon = DesignR.drawable.ic_content_cut,
            onClick = { choosing = FeedChoice.SILENCE },
        )
        ClickRow(
            title = stringResource(R.string.playback_volume_boost),
            value = withDefault(effects.volumeBoostDb, area.volumeBoostDb) { volumeBoostLabel(it) },
            icon = DesignR.drawable.ic_volume_up,
            onClick = { choosing = FeedChoice.BOOST },
        )
        ClickRow(
            title = stringResource(R.string.podcast_skip_intro),
            value = resumeSecondsLabel(feed.skipIntroS),
            icon = DesignR.drawable.ic_first_page,
            onClick = { choosing = FeedChoice.INTRO },
        )
        ClickRow(
            title = stringResource(R.string.podcast_skip_outro),
            value = resumeSecondsLabel(feed.skipOutroS),
            icon = DesignR.drawable.ic_last_page,
            onClick = { choosing = FeedChoice.OUTRO },
        )
    }

    val close = { choosing = null }
    when (choosing) {
        FeedChoice.SPEED -> ChoiceDialog(
            title = stringResource(R.string.playback_default_speed),
            options = listOf<Float?>(null) + (SpokenEffects.SPEED_CHOICES + listOfNotNull(effects.speed)).distinct().sorted(),
            selected = effects.speed,
            label = { withDefault(it, area.speed) { speed -> speedLabel(speed) } },
            onSelect = {
                onEffects { current -> current.copy(speed = it) }
                close()
            },
            onDismiss = close,
        )
        FeedChoice.SILENCE -> ChoiceDialog(
            title = stringResource(R.string.playback_skip_silence),
            options = listOf(null, true, false),
            selected = effects.skipSilence,
            label = { withDefault(it, area.skipSilence, silenceLabel) },
            onSelect = {
                onEffects { current -> current.copy(skipSilence = it) }
                close()
            },
            onDismiss = close,
            description = stringResource(R.string.playback_skip_silence_summary),
        )
        FeedChoice.BOOST -> ChoiceDialog(
            title = stringResource(R.string.playback_volume_boost),
            options = listOf<Int?>(null) + SpokenEffects.VOLUME_BOOST_CHOICES,
            selected = effects.volumeBoostDb,
            label = { withDefault(it, area.volumeBoostDb) { db -> volumeBoostLabel(db) } },
            onSelect = {
                onEffects { current -> current.copy(volumeBoostDb = it) }
                close()
            },
            onDismiss = close,
        )
        FeedChoice.INTRO -> ChoiceDialog(
            title = stringResource(R.string.podcast_skip_intro),
            options = (SpokenEffects.SKIP_CHOICES_S + feed.skipIntroS).distinct().sorted(),
            selected = feed.skipIntroS,
            label = { resumeSecondsLabel(it) },
            onSelect = {
                onSkipIntroOutro(it, feed.skipOutroS)
                close()
            },
            onDismiss = close,
            description = stringResource(R.string.podcast_skip_intro_description),
        )
        FeedChoice.OUTRO -> ChoiceDialog(
            title = stringResource(R.string.podcast_skip_outro),
            options = (SpokenEffects.SKIP_CHOICES_S + feed.skipOutroS).distinct().sorted(),
            selected = feed.skipOutroS,
            label = { resumeSecondsLabel(it) },
            onSelect = {
                onSkipIntroOutro(feed.skipIntroS, it)
                close()
            },
            onDismiss = close,
            description = stringResource(R.string.podcast_skip_outro_description),
        )
        null -> Unit
    }
}

private enum class QueueChoice { MODE, KEEP }

/** What gets into "Continue listening" automatically and how many episodes the lists show (subscription settings). */
@Composable
internal fun FeedQueueRows(feed: PodcastFeed, onQueueMode: (FeedQueueMode) -> Unit, onKeep: (Int) -> Unit) {
    var choosing by rememberSaveable { mutableStateOf<QueueChoice?>(null) }
    val keepLabel: @Composable (Int) -> String = {
        if (it <= 0) stringResource(R.string.podcast_keep_all) else pluralStringResource(R.plurals.podcast_keep_newest, it, it)
    }
    Column {
        ClickRow(
            title = stringResource(R.string.podcast_queue_mode),
            value = stringResource(feed.queueMode.label),
            icon = DesignR.drawable.ic_queue_music,
            onClick = { choosing = QueueChoice.MODE },
        )
        ClickRow(
            title = stringResource(R.string.podcast_keep_episodes),
            value = keepLabel(feed.keepEpisodes),
            icon = DesignR.drawable.ic_list,
            onClick = { choosing = QueueChoice.KEEP },
        )
    }
    val close = { choosing = null }
    when (choosing) {
        QueueChoice.MODE -> ChoiceDialog(
            title = stringResource(R.string.podcast_queue_mode),
            options = FeedQueueMode.entries,
            selected = feed.queueMode,
            label = { stringResource(it.label) },
            onSelect = {
                onQueueMode(it)
                close()
            },
            onDismiss = close,
            description = stringResource(R.string.podcast_queue_mode_description),
        )
        QueueChoice.KEEP -> ChoiceDialog(
            title = stringResource(R.string.podcast_keep_episodes),
            options = (KEEP_CHOICES + feed.keepEpisodes).distinct().sorted(),
            selected = feed.keepEpisodes,
            label = keepLabel,
            onSelect = {
                onKeep(it)
                close()
            },
            onDismiss = close,
            description = stringResource(R.string.podcast_keep_episodes_description),
        )
        null -> Unit
    }
}

/**
 * How many new episodes this subscription's automation keeps ready: the default from Settings → Podcasts ([defaultCount])
 * or a custom count. With "Only the newest" it is always one, and the row is omitted.
 */
@Composable
internal fun FeedAutoCountRow(feed: PodcastFeed, defaultCount: Int, onSelect: (Int?) -> Unit) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    val label: @Composable (Int?) -> String = { count ->
        if (count == null) {
            stringResource(
                R.string.podcast_auto_count_default,
                pluralStringResource(R.plurals.settings_podcast_auto_count_value, defaultCount, defaultCount),
            )
        } else {
            pluralStringResource(R.plurals.settings_podcast_auto_count_value, count, count)
        }
    }
    ClickRow(
        title = stringResource(R.string.settings_podcast_auto_count),
        value = label(feed.autoDownloadCount),
        icon = DesignR.drawable.ic_download,
        onClick = { choosing = true },
    )
    if (choosing) {
        ChoiceDialog(
            title = stringResource(R.string.settings_podcast_auto_count),
            options = listOf<Int?>(null) + (PodcastSettings.AUTO_DOWNLOAD_COUNT_CHOICES + listOfNotNull(feed.autoDownloadCount)).distinct().sorted(),
            selected = feed.autoDownloadCount,
            label = label,
            onSelect = {
                onSelect(it)
                choosing = false
            },
            onDismiss = { choosing = false },
        )
    }
}

private val FeedQueueMode.label: Int
    get() = when (this) {
        FeedQueueMode.ALL_NEW -> R.string.podcast_queue_mode_all
        FeedQueueMode.NEWEST_ONLY -> R.string.podcast_queue_mode_newest
        FeedQueueMode.MANUAL -> R.string.podcast_queue_mode_manual
    }

/** "Keep episodes": 0 = all. */
private val KEEP_CHOICES = listOf(0, 1, 3, 5, 10, 25, 50)

/** The subscription's value, without its own "Default (value of the area)". */
@Composable
private fun <T : Any> withDefault(value: T?, area: T, label: @Composable (T) -> String): String =
    if (value != null) label(value) else stringResource(R.string.playback_default_value, label(area))
