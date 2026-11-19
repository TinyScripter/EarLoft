package de.kleinabersmart.earloft.core.model

/**
 * Listening time of a podcast. [feedId] is `null` if it is not (or no longer) subscribed; [archived] if its subscription
 * is archived (then also without [feedId]). [episodes] is 0 if the number is unknown (year view).
 */
data class PodcastListening(
    val key: String,
    val title: String,
    val listenedMs: Long,
    val episodes: Int,
    val feedId: String?,
    val archived: Boolean = false,
)

/** Listening time in a calendar month (only what the app counted itself). */
data class MonthListening(val year: Int, val month: Int, val listenedMs: Long)

/**
 * Podcast statistics. [year] is `null` for "Total" (then [months] are the last twelve months, the current one
 * last), otherwise the selected year (then [months] are January to December and only self-counted time is included).
 * [years] are the selectable years, newest first.
 */
data class PodcastStats(
    val totalMs: Long,
    val finishedEpisodes: Int,
    val subscriptions: Int,
    val podcasts: List<PodcastListening>,
    val months: List<MonthListening>,
    val years: List<Int> = emptyList(),
    val year: Int? = null,
    /** Time saved by "Trim silence" (wall clock), in the selected year or in total; only self-counted. */
    val silenceSavedMs: Long = 0,
) {
    /** Whether the history has any numbers at all (otherwise the UI shows no chart). */
    val hasHistory: Boolean get() = months.any { it.listenedMs > 0 }
}

/** Listening time of a book. [bookId] is `null` if the book no longer exists in the app. */
data class BookListening(val key: String, val title: String, val listenedMs: Long, val bookId: Long?)

/** Listening time on a weekday; [dayOfWeek] counts from 1 (Monday) to 7 (Sunday). */
data class WeekdayListening(val dayOfWeek: Int, val listenedMs: Long)

/** What the app itself counted for audiobooks. [months], [years] and [year] as in [PodcastStats]; within a year [books] is empty. */
data class DeviceBookStats(
    val totalMs: Long,
    val finishedBooks: Int,
    val books: List<BookListening>,
    val months: List<MonthListening>,
    val years: List<Int> = emptyList(),
    val year: Int? = null,
    /** As in [PodcastStats]. */
    val silenceSavedMs: Long = 0,
) {
    val hasHistory: Boolean get() = months.any { it.listenedMs > 0 }
}

/**
 * The account's listening time as Audiobookshelf counts it from the sessions of all devices. [fetchedAt] is the time of the
 * fetch (Unix ms); [streakDays] are the consecutive days up to today (or yesterday) with listening. [months], [years]
 * and [year] as in [PodcastStats]; within a year [books] is empty and [weekdays] comes from that year's days.
 */
data class ServerBookStats(
    val totalMs: Long,
    val todayMs: Long,
    val streakDays: Int,
    val books: List<BookListening>,
    val months: List<MonthListening>,
    val weekdays: List<WeekdayListening>,
    val fetchedAt: Long,
    val years: List<Int> = emptyList(),
    val year: Int? = null,
) {
    val hasHistory: Boolean get() = months.any { it.listenedMs > 0 }
}

/**
 * An entry of the music statistics' top lists. [id] is the song, album or artist (`null` for genres), [albumId] the
 * album for the cover (`null` for artists and genres); [subtitle] the artist of a song or album.
 */
data class MusicTopEntry(
    val id: Long?,
    val title: String,
    val subtitle: String?,
    val albumId: Long?,
    val plays: Int,
    val listenedMs: Long,
)

/**
 * Music statistics from the listening history (counted plays, concept 8 "music statistics"). [songs] and [albums] count distinct
 * songs or albums; [newAlbums] the albums first played in the selected year (in "Total" equal to
 * [albums]). [months], [years] and [year] as in [PodcastStats]; [weekdays] from the selected period.
 */
data class MusicStats(
    val totalMs: Long,
    val plays: Int,
    val songs: Int,
    val albums: Int,
    val newAlbums: Int,
    val topArtists: List<MusicTopEntry>,
    val topAlbums: List<MusicTopEntry>,
    val topSongs: List<MusicTopEntry>,
    val topGenres: List<MusicTopEntry>,
    val months: List<MonthListening>,
    val weekdays: List<WeekdayListening>,
    val years: List<Int> = emptyList(),
    val year: Int? = null,
) {
    val hasHistory: Boolean get() = months.any { it.listenedMs > 0 }
}
