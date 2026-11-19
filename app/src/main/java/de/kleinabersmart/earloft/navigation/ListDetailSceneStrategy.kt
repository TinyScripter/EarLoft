package de.kleinabersmart.earloft.navigation

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
 * On wide windows (tablet in landscape, unfolded foldable) a detail page sits to the right of its list,
 * e.g. an artist next to the library or "About" next to the settings.
 *
 * As long as no detail page is open, the list takes the full width (no empty placeholder, the
 * album grid stays wide). List with and without detail are the same scene, so that opening and closing only
 * animates the width and the list doesn't fade out and back in.
 *
 * Which destinations are list or detail is defined in the entries' metadata ([listPane], [detailPane],
 * [nestedDetailPane]).
 */
class ListDetailSceneStrategy(private val twoPane: Boolean) : SceneStrategy<NavKey> {

    override fun SceneStrategyScope<NavKey>.calculateScene(entries: List<NavEntry<NavKey>>): Scene<NavKey>? {
        if (!twoPane || entries.isEmpty()) return null
        // Skip detail pages from the top until the corresponding list comes.
        var index = entries.lastIndex
        while (index >= 0 && entries.isDetailAt(index)) index--
        if (index < 0 || entries[index].metadata[PANE_KEY] != PANE_LIST) return null
        val list = entries[index]
        val detail = entries.last().takeIf { index < entries.lastIndex }
        return ListDetailScene(
            list = list,
            detail = detail,
            // Only the first detail level closes the pane with "Back"; above that it leads to the previous detail page.
            detailClosesPane = index == entries.lastIndex - 1,
            previousEntries = entries.dropLast(1),
        )
    }

    private fun List<NavEntry<NavKey>>.isDetailAt(index: Int): Boolean = when (this[index].metadata[PANE_KEY]) {
        PANE_DETAIL -> true
        // Only on top of another detail page (Library → Artist → Album), otherwise a page of its own (Library → Album).
        PANE_NESTED_DETAIL -> index > 0 && getOrNull(index - 1)?.metadata?.get(PANE_KEY).let {
            it == PANE_DETAIL || it == PANE_NESTED_DETAIL
        }
        else -> false
    }

    companion object {
        private const val PANE_KEY = "earloft.pane"
        private const val PANE_LIST = "list"
        private const val PANE_DETAIL = "detail"
        private const val PANE_NESTED_DETAIL = "nestedDetail"

        /** List next to which detail pages appear. */
        fun listPane(): Map<String, Any> = mapOf(PANE_KEY to PANE_LIST)

        /** Detail page that appears next to its list. */
        fun detailPane(): Map<String, Any> = mapOf(PANE_KEY to PANE_DETAIL)

        /** Detail page only if it was opened from another detail page; directly from the list it's a full page. */
        fun nestedDetailPane(): Map<String, Any> = mapOf(PANE_KEY to PANE_NESTED_DETAIL)
    }
}

/**
 * `true` for a detail page that sits directly next to its list: "Back" closes the right pane there,
 * so [de.kleinabersmart.earloft.ui.library.BackButton] shows an ✕ instead of the arrow.
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
