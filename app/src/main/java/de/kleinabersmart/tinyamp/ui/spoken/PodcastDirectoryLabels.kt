package de.kleinabersmart.tinyamp.ui.spoken

import androidx.annotation.StringRes
import de.kleinabersmart.tinyamp.R
import de.kleinabersmart.tinyamp.core.network.podcast.directory.PodcastDirectory
import java.util.Locale

/** Name des Verzeichnisses. */
@get:StringRes
val PodcastDirectory.label: Int
    get() = when (this) {
        PodcastDirectory.APPLE -> R.string.directory_apple
        PodcastDirectory.PODCAST_INDEX -> R.string.directory_podcast_index
        PodcastDirectory.FYYD -> R.string.directory_fyyd
        PodcastDirectory.ARD_SOUNDS -> R.string.directory_ard_sounds
    }

/** Zeile unter dem Schalter in den Einstellungen: was das Verzeichnis ist und dass die Suchbegriffe dorthin gehen. */
@get:StringRes
val PodcastDirectory.summary: Int
    get() = when (this) {
        PodcastDirectory.APPLE -> R.string.directory_apple_summary
        PodcastDirectory.PODCAST_INDEX -> R.string.directory_podcast_index_summary
        PodcastDirectory.FYYD -> R.string.directory_fyyd_summary
        PodcastDirectory.ARD_SOUNDS -> R.string.directory_ard_sounds_summary
    }

/** Name eines Landes (ISO 3166) in der Sprache der App. */
fun countryName(code: String, locale: Locale = Locale.getDefault()): String =
    Locale.Builder().setRegion(code).build().getDisplayCountry(locale).ifBlank { code }
