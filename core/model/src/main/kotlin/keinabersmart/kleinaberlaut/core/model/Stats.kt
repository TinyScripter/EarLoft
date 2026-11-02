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
