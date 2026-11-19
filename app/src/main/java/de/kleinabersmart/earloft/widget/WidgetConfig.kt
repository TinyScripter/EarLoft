package de.kleinabersmart.earloft.widget

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import de.kleinabersmart.earloft.core.model.SpokenMode
import de.kleinabersmart.earloft.core.playback.NowPlaying

/** Colors of a widget: from the cover, the app colors (logo) or the system colors (Material You, Android 12+). */
enum class WidgetColors { COVER, APP, SYSTEM }

/**
 * Settings of a single widget, chosen when it is added (note 8.14): which sections it covers and how it is
 * colored. This allows several widgets side by side, e.g. one for music and one for podcasts and books.
 */
data class WidgetConfig(
    val areas: Set<SpokenMode> = SpokenMode.entries.toSet(),
    val colors: WidgetColors = WidgetColors.COVER,
)

private val KEY_AREAS = stringSetPreferencesKey("areas")
private val KEY_COLORS = stringPreferencesKey("colors")

/** From a widget's state (Glance, per widget); `null` as long as it has not been configured. */
fun Preferences.widgetConfig(): WidgetConfig? {
    val areas = this[KEY_AREAS]?.mapNotNullTo(HashSet()) { name -> SpokenMode.entries.firstOrNull { it.name == name } }
    val colors = this[KEY_COLORS]?.let { name -> WidgetColors.entries.firstOrNull { it.name == name } }
    if (areas.isNullOrEmpty() || colors == null) return null
    return WidgetConfig(areas, colors)
}

fun MutablePreferences.setWidgetConfig(config: WidgetConfig) {
    this[KEY_AREAS] = config.areas.mapTo(HashSet()) { it.name }
    this[KEY_COLORS] = config.colors.name
}

/** The section of what is playing. */
val NowPlaying.area: SpokenMode
    get() = when {
        bookId == null -> SpokenMode.MUSIC
        isEpisode -> SpokenMode.PODCASTS
        else -> SpokenMode.BOOKS
    }
