package de.kleinabersmart.kleinaberlaut.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope

/**
 * Auf breiten Fenstern (Tablet quer, aufgeklapptes Foldable) steht eine Detailseite rechts neben ihrer Liste,
 * z. B. Künstler neben der Bibliothek oder „Über“ neben den Einstellungen.
 *
 * Solange keine Detailseite offen ist, nimmt die Liste die ganze Breite ein (kein leerer Platzhalter, das
 * Album-Raster bleibt breit). Liste mit und ohne Detail sind dieselbe Scene, damit beim Öffnen und Schließen nur
 * die Breite animiert und die Liste nicht aus- und wieder einblendet.
 *
 * Welche Ziele Liste oder Detail sind, steht in den Metadaten der Einträge ([listPane], [detailPane],
 * [nestedDetailPane]).
 */
class ListDetailSceneStrategy(private val twoPane: Boolean) : SceneStrategy<NavKey> {

    override fun SceneStrategyScope<NavKey>.calculateScene(entries: List<NavEntry<NavKey>>): Scene<NavKey>? {
        if (!twoPane || entries.isEmpty()) return null
        // Von oben so lange Detailseiten überspringen, bis die zugehörige Liste kommt.
        var index = entries.lastIndex
        while (index >= 0 && entries.isDetailAt(index)) index--
        if (index < 0 || entries[index].metadata[PANE_KEY] != PANE_LIST) return null
        val list = entries[index]
        val detail = entries.last().takeIf { index < entries.lastIndex }
        return ListDetailScene(
            list = list,
            detail = detail,
            // Nur die erste Detailebene schließt mit „Zurück“ die Spalte; darüber führt es zur vorigen Detailseite.
            detailClosesPane = index == entries.lastIndex - 1,
            previousEntries = entries.dropLast(1),
        )
    }

    private fun List<NavEntry<NavKey>>.isDetailAt(index: Int): Boolean = when (this[index].metadata[PANE_KEY]) {
        PANE_DETAIL -> true
        // Nur über einer anderen Detailseite (Bibliothek → Künstler → Album), sonst eigene Seite (Bibliothek → Album).
        PANE_NESTED_DETAIL -> index > 0 && getOrNull(index - 1)?.metadata?.get(PANE_KEY).let {
            it == PANE_DETAIL || it == PANE_NESTED_DETAIL
        }
        else -> false
    }

    companion object {
        private const val PANE_KEY = "kleinaberlaut.pane"
        private const val PANE_LIST = "list"
        private const val PANE_DETAIL = "detail"
        private const val PANE_NESTED_DETAIL = "nestedDetail"

        /** Liste, neben der Detailseiten erscheinen. */
        fun listPane(): Map<String, Any> = mapOf(PANE_KEY to PANE_LIST)

        /** Detailseite, die neben ihrer Liste erscheint. */
        fun detailPane(): Map<String, Any> = mapOf(PANE_KEY to PANE_DETAIL)

        /** Detailseite nur, wenn sie aus einer anderen Detailseite geöffnet wurde; direkt aus der Liste ganze Seite. */
        fun nestedDetailPane(): Map<String, Any> = mapOf(PANE_KEY to PANE_NESTED_DETAIL)
    }
}

/**
 * `true` für eine Detailseite, die direkt neben ihrer Liste steht: „Zurück“ schließt dort die rechte Spalte,
 * daher zeigt [de.kleinabersmart.kleinaberlaut.ui.library.BackButton] ein ✕ statt des Pfeils.
 */
val LocalClosesDetailPane = staticCompositionLocalOf { false }

private class ListDetailScene(
    val list: NavEntry<NavKey>,
    val detail: NavEntry<NavKey>?,
    val detailClosesPane: Boolean,
    override val previousEntries: List<NavEntry<NavKey>>,
) : Scene<NavKey> {
    override val key: Any = list.contentKey
    override val entries: List<NavEntry<NavKey>> = listOfNotNull(list, detail)

    override val content: @Composable () -> Unit = {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val listWidth by animateDpAsState(
                targetValue = if (detail == null) maxWidth else (maxWidth * 0.4f).coerceAtLeast(360.dp),
                animationSpec = tween(300),
                label = "listPaneWidth",
            )
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(listWidth).fillMaxHeight()) { list.Content() }
                if (detail != null) {
                    VerticalDivider()
                    AnimatedContent(
                        targetState = detail,
                        contentKey = { it.contentKey },
                        transitionSpec = { fadeIn(tween(210, delayMillis = 90)) togetherWith fadeOut(tween(90)) },
                        label = "detailPane",
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) { entry ->
                        CompositionLocalProvider(
                            LocalClosesDetailPane provides (detailClosesPane && entry.contentKey == detail.contentKey),
                        ) { entry.Content() }
                    }
                }
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ListDetailScene && list == other.list && detail == other.detail &&
            detailClosesPane == other.detailClosesPane && previousEntries == other.previousEntries

    override fun hashCode(): Int =
        ((list.hashCode() * 31 + detail.hashCode()) * 31 + detailClosesPane.hashCode()) * 31 + previousEntries.hashCode()
}
