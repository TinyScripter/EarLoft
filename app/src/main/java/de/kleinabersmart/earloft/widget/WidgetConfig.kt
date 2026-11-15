package de.kleinabersmart.earloft.widget

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import de.kleinabersmart.earloft.core.model.SpokenMode
import de.kleinabersmart.earloft.core.playback.NowPlaying

/** Farben eines Widgets: aus dem Cover, die App-Farben (Logo) oder die Systemfarben (Material You, ab Android 12). */
enum class WidgetColors { COVER, APP, SYSTEM }

/**
 * Einstellungen eines einzelnen Widgets, beim Anlegen gewählt (Notiz 8.14): für welche Bereiche es gilt und wie es sich
 * färbt. So lassen sich mehrere Widgets nebeneinander nutzen, etwa eins für Musik und eins für Podcasts und Bücher.
 */
data class WidgetConfig(
    val areas: Set<SpokenMode> = SpokenMode.entries.toSet(),
    val colors: WidgetColors = WidgetColors.COVER,
)

private val KEY_AREAS = stringSetPreferencesKey("areas")
private val KEY_COLORS = stringPreferencesKey("colors")

/** Aus dem Zustand eines Widgets (Glance, je Widget); `null`, solange es nicht eingestellt ist. */
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

/** Der Bereich dessen, was läuft. */
val NowPlaying.area: SpokenMode
    get() = when {
        bookId == null -> SpokenMode.MUSIC
        isEpisode -> SpokenMode.PODCASTS
        else -> SpokenMode.BOOKS
    }
