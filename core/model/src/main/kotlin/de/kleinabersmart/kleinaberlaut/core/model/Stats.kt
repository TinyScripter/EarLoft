package de.kleinabersmart.kleinaberlaut.core.model

/**
 * Gehörte Zeit eines Podcasts. [feedId] ist `null`, wenn er nicht (mehr) abonniert ist; [archived], wenn sein Abo
 * archiviert ist (dann auch ohne [feedId]). [episodes] ist 0, wenn die Zahl nicht bekannt ist (Jahresansicht).
 */
data class PodcastListening(
    val key: String,
    val title: String,
    val listenedMs: Long,
    val episodes: Int,
    val feedId: String?,
    val archived: Boolean = false,
)

/** Gehörte Zeit in einem Kalendermonat (nur was die App selbst gezählt hat). */
data class MonthListening(val year: Int, val month: Int, val listenedMs: Long)

/**
 * Statistik der Podcasts. [year] ist `null` für „Gesamt“ (dann sind [months] die letzten zwölf Monate, der aktuelle
 * zuletzt), sonst das gewählte Jahr (dann sind [months] Januar bis Dezember und nur selbst gezählte Zeit ist enthalten).
 * [years] sind die wählbaren Jahre, das neueste zuerst.
 */
data class PodcastStats(
    val totalMs: Long,
    val finishedEpisodes: Int,
    val subscriptions: Int,
    val podcasts: List<PodcastListening>,
    val months: List<MonthListening>,
    val years: List<Int> = emptyList(),
    val year: Int? = null,
) {
    /** Ob der Verlauf überhaupt Zahlen hat (sonst zeigt die Oberfläche kein Diagramm). */
    val hasHistory: Boolean get() = months.any { it.listenedMs > 0 }
}

/** Gehörte Zeit eines Buchs. [bookId] ist `null`, wenn es das Buch in der App nicht (mehr) gibt. */
data class BookListening(val key: String, val title: String, val listenedMs: Long, val bookId: Long?)

/** Gehörte Zeit an einem Wochentag; [dayOfWeek] zählt von 1 (Montag) bis 7 (Sonntag). */
data class WeekdayListening(val dayOfWeek: Int, val listenedMs: Long)

/** Was die App selbst von Hörbüchern gezählt hat. [months], [years] und [year] wie bei [PodcastStats]; in einem Jahr ist [books] leer. */
data class DeviceBookStats(
    val totalMs: Long,
    val finishedBooks: Int,
    val books: List<BookListening>,
    val months: List<MonthListening>,
    val years: List<Int> = emptyList(),
    val year: Int? = null,
) {
    val hasHistory: Boolean get() = months.any { it.listenedMs > 0 }
}

/**
 * Die Hörzeit des Kontos, wie Audiobookshelf sie aus den Sitzungen aller Geräte zählt. [fetchedAt] ist der Zeitpunkt des
 * Abrufs (Unix-ms); [streakDays] sind die Tage in Folge bis heute (oder gestern), an denen gehört wurde. [months], [years]
 * und [year] wie bei [PodcastStats]; in einem Jahr ist [books] leer und [weekdays] aus den Tagen dieses Jahres.
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
