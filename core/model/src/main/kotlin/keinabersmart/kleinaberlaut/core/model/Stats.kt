package keinabersmart.kleinaberlaut.core.model

/** Gehörte Zeit eines Podcasts. [feedId] ist `null`, wenn er nicht (mehr) abonniert ist. */
data class PodcastListening(
    val key: String,
    val title: String,
    val listenedMs: Long,
    val episodes: Int,
    val feedId: String?,
)

/** Gehörte Zeit in einem Kalendermonat (nur was die App selbst gezählt hat). */
data class MonthListening(val year: Int, val month: Int, val listenedMs: Long)

/** Statistik der Podcasts. [months] sind die letzten zwölf Monate, der aktuelle zuletzt. */
data class PodcastStats(
    val totalMs: Long,
    val finishedEpisodes: Int,
    val subscriptions: Int,
    val podcasts: List<PodcastListening>,
    val months: List<MonthListening>,
) {
    /** Ob der Verlauf überhaupt Zahlen hat (sonst zeigt die Oberfläche kein Diagramm). */
    val hasHistory: Boolean get() = months.any { it.listenedMs > 0 }
}

/** Gehörte Zeit eines Buchs. [bookId] ist `null`, wenn es das Buch in der App nicht (mehr) gibt. */
data class BookListening(val key: String, val title: String, val listenedMs: Long, val bookId: Long?)

/** Gehörte Zeit an einem Wochentag; [dayOfWeek] zählt von 1 (Montag) bis 7 (Sonntag). */
data class WeekdayListening(val dayOfWeek: Int, val listenedMs: Long)

/** Was die App selbst von Hörbüchern gezählt hat. [months] wie bei [PodcastStats]. */
data class DeviceBookStats(
    val totalMs: Long,
    val finishedBooks: Int,
    val books: List<BookListening>,
    val months: List<MonthListening>,
) {
    val hasHistory: Boolean get() = months.any { it.listenedMs > 0 }
}

/**
 * Die Hörzeit des Kontos, wie Audiobookshelf sie aus den Sitzungen aller Geräte zählt. [fetchedAt] ist der Zeitpunkt des
 * Abrufs (Unix-ms); [streakDays] sind die Tage in Folge bis heute (oder gestern), an denen gehört wurde.
 */
data class ServerBookStats(
    val totalMs: Long,
    val todayMs: Long,
    val streakDays: Int,
    val books: List<BookListening>,
    val months: List<MonthListening>,
    val weekdays: List<WeekdayListening>,
    val fetchedAt: Long,
) {
    val hasHistory: Boolean get() = months.any { it.listenedMs > 0 }
}
