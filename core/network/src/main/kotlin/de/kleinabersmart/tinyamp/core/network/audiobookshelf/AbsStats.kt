package de.kleinabersmart.tinyamp.core.network.audiobookshelf

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Antwort von `GET /api/me/listening-stats`. Zeiten in Sekunden. [days] ist nach `YYYY-MM-DD` geschlüsselt, [dayOfWeek]
 * nach englischem Wochentagsnamen, [items] nach der ID des Eintrags.
 */
@Serializable
data class AbsListeningStats(
    val totalTime: Double = 0.0,
    val items: Map<String, AbsStatItem> = emptyMap(),
    val days: Map<String, Double> = emptyMap(),
    val dayOfWeek: Map<String, Double> = emptyMap(),
    val today: Double = 0.0,
)

@Serializable
data class AbsStatItem(
    val id: String = "",
    val timeListening: Double = 0.0,
    val mediaMetadata: AbsStatMetadata? = null,
    val lastUpdate: Long = 0,
)

@Serializable
data class AbsStatMetadata(
    val title: String = "",
    val authorName: String? = null,
)

/** Wandelt die Antwort in [AbsListeningStats]; unbekannte Felder werden ignoriert. */
object AbsStats {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun parse(text: String): AbsListeningStats =
        try {
            json.decodeFromString(text)
        } catch (e: SerializationException) {
            throw AbsException(0, "Unerwartete Antwort: ${e.message}")
        }
}

/**
 * Eine Hörsitzung, wie Clients sie `POST /api/session/local` melden, wenn sie ohne Server-Sitzung abspielen. Zeiten in
 * Sekunden, [startedAt] und [updatedAt] in Unix-ms; wiederholte Meldungen mit derselben [id] ersetzen die Summen.
 * (Ohne Standardwerte, damit alle Felder gesendet werden.)
 */
@Serializable
data class AbsLocalSession(
    val id: String,
    val userId: String,
    val libraryItemId: String,
    val mediaType: String,
    val displayTitle: String,
    val displayAuthor: String,
    val duration: Double,
    /** 3 = lokal (ohne Server-Stream). */
    val playMethod: Int,
    val mediaPlayer: String,
    val deviceInfo: AbsDeviceInfo,
    val date: String,
    val dayOfWeek: String,
    val timeListening: Double,
    val startTime: Double,
    val currentTime: Double,
    val startedAt: Long,
    val updatedAt: Long,
)

@Serializable
data class AbsDeviceInfo(
    val deviceId: String,
    val clientName: String,
    val clientVersion: String,
    val manufacturer: String,
    val model: String,
)
