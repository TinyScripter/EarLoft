package de.kleinabersmart.earloft.core.network.audiobookshelf

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Response of `GET /api/me/listening-stats`. Times in seconds. [days] is keyed by `YYYY-MM-DD`, [dayOfWeek]
 * by English weekday name, [items] by the entry's ID.
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

/** Converts the response into [AbsListeningStats]; unknown fields are ignored. */
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
            throw AbsException(0, "Unexpected response: ${e.message}")
        }
}

/**
 * A listening session as clients report it via `POST /api/session/local` when they play without a server session. Times in
 * seconds, [startedAt] and [updatedAt] in Unix ms; repeated reports with the same [id] replace the totals.
 * (Without default values, so that all fields are sent.)
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
    /** 3 = local (without a server stream). */
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
