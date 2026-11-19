package de.kleinabersmart.earloft.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.model.SongSourceInfo
import de.kleinabersmart.earloft.core.model.SourceType
import de.kleinabersmart.earloft.core.playback.PlayedFrom
import androidx.compose.ui.platform.LocalConfiguration
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/**
 * Track info (concept 1 "Fidelity"): where the song is currently playing from (device, download, stream) and which sources provide it,
 * each with format, bitrate, size and ReplayGain. The source being played has a check mark.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackInfoSheet(
    songId: Long,
    title: String,
    onDismiss: () -> Unit,
    viewModel: TrackInfoViewModel = hiltViewModel(),
) {
    val info by remember(songId) { viewModel.info(songId) }.collectAsStateWithLifecycle(null)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.track_info_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            val current = info ?: return@Column
            ListItem(
                headlineContent = { Text(stringResource(R.string.track_info_playing_from)) },
                supportingContent = { Text(playedFromText(current)) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            current.sources.forEach { source ->
                ListItem(
                    headlineContent = { Text(sourceTitle(source)) },
                    supportingContent = { Text(sourceDetails(source)) },
                    leadingContent = {
                        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                            if (isPlayed(source, current.playedFrom)) {
                                Icon(
                                    painterResource(DesignR.drawable.ic_check),
                                    contentDescription = stringResource(R.string.track_info_played_source),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
}

@Composable
private fun playedFromText(info: TrackInfo): String {
    val played = info.playedFrom ?: return stringResource(R.string.track_info_not_loaded)
    return when (played.kind) {
        PlayedFrom.Kind.DEVICE -> stringResource(R.string.track_info_from_device)
        PlayedFrom.Kind.DOWNLOAD -> info.sources.firstOrNull { it.isDownloaded }
            ?.let { stringResource(R.string.track_info_from_download_of, it.sourceName) }
            ?: stringResource(R.string.track_info_from_download)
        PlayedFrom.Kind.STREAM -> {
            val stream = stringResource(
                R.string.track_info_from_stream,
                info.sources.firstOrNull { it.sourceId == played.sourceId }?.sourceName ?: played.sourceId.orEmpty(),
            )
            // Over mobile data (concept 7.4): the server transcodes, so the source's format and bitrate don't apply.
            played.transcodedTo?.let { (codec, kbps) ->
                stringResource(R.string.track_info_transcoded, stream, formatLabel("audio/$codec") ?: codec, kbps)
            } ?: stream
        }
    }
}

@Composable
private fun sourceTitle(source: SongSourceInfo): String = when {
    source.sourceType == SourceType.LOCAL -> stringResource(R.string.track_info_source_device)
    source.isDownloaded -> stringResource(R.string.track_info_source_downloaded, source.sourceName)
    else -> source.sourceName
}

/** "FLAC · 1 012 kbit/s · 34,2 MB · ReplayGain −6,3 dB (Album −7,1 dB)"; whatever is missing is omitted. */
@Composable
private fun sourceDetails(source: SongSourceInfo): String {
    val locale = LocalConfiguration.current.locales[0]
    val gain = source.trackGainDb?.let { track ->
        val album = source.albumGainDb?.let { stringResource(R.string.track_info_album_gain, "%+.1f".format(locale, it)) }.orEmpty()
        stringResource(R.string.track_info_gain, "%+.1f".format(locale, track)) + album
    }
    return listOfNotNull(
        formatLabel(source.mimeType),
        source.bitRate?.takeIf { it > 0 }?.let { stringResource(R.string.track_info_kbps, it / 1000) },
        source.sizeBytes?.takeIf { it > 0 }?.let { "%.1f MB".format(locale, it / 1_000_000.0) },
        gain,
    ).joinToString(" · ").ifEmpty { "–" }
}

private fun isPlayed(source: SongSourceInfo, played: PlayedFrom?): Boolean = when (played?.kind) {
    PlayedFrom.Kind.DEVICE, PlayedFrom.Kind.STREAM -> source.sourceId == played.sourceId
    PlayedFrom.Kind.DOWNLOAD -> source.isDownloaded
    null -> false
}

/** Short name of the format from the MIME type, e.g. `audio/flac` → "FLAC". */
internal fun formatLabel(mimeType: String?): String? {
    val subtype = mimeType?.substringAfter('/', "")?.lowercase()?.removePrefix("x-")?.takeIf { it.isNotBlank() } ?: return null
    return when (subtype) {
        "mpeg", "mp3" -> "MP3"
        "mp4", "m4a", "aac", "mp4a-latm" -> "AAC/M4A"
        "ogg", "vorbis" -> "Ogg Vorbis"
        "opus" -> "Opus"
        "wav", "wave" -> "WAV"
        "aiff" -> "AIFF"
        "ms-wma" -> "WMA"
        else -> subtype.uppercase()
    }
}
