package de.kleinabersmart.earloft.ui.spoken

import androidx.annotation.StringRes
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.network.podcast.directory.PodcastDirectory
import java.util.Locale

/** Name of the directory. */
@get:StringRes
val PodcastDirectory.label: Int
    get() = when (this) {
        PodcastDirectory.APPLE -> R.string.directory_apple
        PodcastDirectory.PODCAST_INDEX -> R.string.directory_podcast_index
        PodcastDirectory.FYYD -> R.string.directory_fyyd
        PodcastDirectory.ARD_SOUNDS -> R.string.directory_ard_sounds
    }

/** Line below the switch in the settings: what the directory is and that the search terms are sent there. */
@get:StringRes
val PodcastDirectory.summary: Int
    get() = when (this) {
        PodcastDirectory.APPLE -> R.string.directory_apple_summary
        PodcastDirectory.PODCAST_INDEX -> R.string.directory_podcast_index_summary
        PodcastDirectory.FYYD -> R.string.directory_fyyd_summary
        PodcastDirectory.ARD_SOUNDS -> R.string.directory_ard_sounds_summary
    }

/** Name of a country (ISO 3166) in the app's language. */
fun countryName(code: String, locale: Locale = Locale.getDefault()): String =
    Locale.Builder().setRegion(code).build().getDisplayCountry(locale).ifBlank { code }
