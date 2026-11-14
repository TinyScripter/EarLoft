package de.kleinabersmart.earloft.ui.spoken

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.model.Book
import de.kleinabersmart.earloft.core.model.EpisodePerson
import de.kleinabersmart.earloft.core.model.EpisodeType
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/**
 * Art und Nummer einer Folge aus dem Feed (Konzept 12.5 „Podcasting 2.0“): „Folge 12“, „Bonus · Folge 3“, „Trailer“, mit
 * [withSeason] auch „Staffel 2 · Folge 12“. In Listen ([withSeason] aus) kurz „#12“, damit Datum und Dauer in die Zeile
 * passen. `null`, wenn der Feed nichts davon nennt.
 */
@Composable
internal fun episodeLabel(book: Book, withSeason: Boolean): String? {
    val parts = listOfNotNull(
        when (book.episodeType) {
            EpisodeType.TRAILER -> stringResource(R.string.episode_type_trailer)
            EpisodeType.BONUS -> stringResource(R.string.episode_type_bonus)
            EpisodeType.FULL, null -> null
        },
        book.season?.takeIf { withSeason }?.let { stringResource(R.string.podcast_season, it) },
        book.episodeNumber?.let { if (withSeason) stringResource(R.string.episode_number, it) else "#$it" },
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/**
 * Teilt die Folgenliste eines Abos in Staffeln, wo die Staffel wechselt (die Liste bleibt nach Datum geordnet). `null`, wenn
 * die Folgen höchstens eine Staffel nennen: Dann braucht es keine Kopfzeilen.
 */
internal fun <T> seasonRuns(items: List<T>, season: (T) -> Int?): List<Pair<Int?, List<T>>>? {
    if (items.mapNotNullTo(HashSet(), season).size < 2) return null
    val runs = mutableListOf<Pair<Int?, MutableList<T>>>()
    for (item in items) {
        val value = season(item)
        if (runs.isEmpty() || runs.last().first != value) runs += value to mutableListOf()
        runs.last().second += item
    }
    return runs
}

/** Kopfzeile einer Staffel; Folgen ohne Staffel stehen unter „Weitere Folgen“. */
@Composable
internal fun seasonTitle(season: Int?): String =
    if (season != null) stringResource(R.string.podcast_season, season) else stringResource(R.string.podcast_season_other)

/** Knopf „Unterstützen“ auf der Abo-Seite (`podcast:funding`); öffnet die Seite im Browser. */
@Composable
internal fun FundingButton(url: String, label: String, size: Dp) {
    val openLink = rememberOpenLink()
    val description = if (label.isNotBlank()) stringResource(R.string.podcast_support_with, label) else stringResource(R.string.podcast_support)
    IconButton(onClick = { openLink(url) }, modifier = Modifier.size(size)) {
        Icon(painterResource(DesignR.drawable.ic_volunteer_activism), contentDescription = description)
    }
}

/**
 * Mitwirkende einer Folge (`podcast:person`) als Zeile kleiner Karten: Bild (sonst Symbol), Name und Rolle. Hat eine Person
 * eine Webseite, öffnet ein Tipp sie.
 */
@Composable
internal fun EpisodePersons(persons: List<EpisodePerson>, modifier: Modifier = Modifier) {
    val openLink = rememberOpenLink()
    Column(modifier) {
        Text(
            stringResource(R.string.episode_persons),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            persons.forEach { person ->
                val url = person.url
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.clip(RoundedCornerShape(24.dp)).then(if (url != null) Modifier.clickable { openLink(url) } else Modifier),
                ) {
                    Row(Modifier.padding(start = 4.dp, end = 14.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        PersonImage(person.imageUrl)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(person.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                personRole(person.role),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonImage(url: String?) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(DesignR.drawable.ic_person),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(36.dp))
    }
}

/**
 * Rolle aus der Podcasting-2.0-Taxonomie in der Sprache der App; ohne Angabe gilt „host“. Seltene Rollen bleiben, wie der
 * Feed sie nennt (mit großem Anfangsbuchstaben).
 */
@Composable
private fun personRole(role: String?): String = when (role ?: "host") {
    "host" -> stringResource(R.string.person_role_host)
    "co-host" -> stringResource(R.string.person_role_cohost)
    "guest" -> stringResource(R.string.person_role_guest)
    "producer", "executive producer" -> stringResource(R.string.person_role_producer)
    "editor" -> stringResource(R.string.person_role_editor)
    else -> role.orEmpty().replaceFirstChar { it.uppercase() }
}
