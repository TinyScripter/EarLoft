package keinabersmart.kleinaberlaut.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** Abstand einer Gruppe zum Bildschirmrand; Überschriften ([SectionHeader]) richten sich am Inhalt der Gruppe aus. */
val GroupMargin = 8.dp

private val GroupCorner = 20.dp

/**
 * Eintrag [index] von [count] einer Listengruppe: Zusammen bilden die Einträge eine Fläche mit runden Ecken oben am ersten
 * und unten am letzten. Zeilen darin (z. B. `ListItem`) liegen auf dieser Fläche, ohne eigene Farben anzugeben.
 */
@Composable
fun GroupedItem(index: Int, count: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val top = if (index == 0) GroupCorner else 0.dp
    val bottom = if (index == count - 1) GroupCorner else 0.dp
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = GroupMargin)
            .clip(RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom))
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        OnGroupSurface(content)
    }
}

/** Feste (nicht scrollende) Gruppe: alle Zeilen aus [content] auf einer Fläche wie bei [GroupedItem]. */
@Composable
fun ListGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = GroupMargin)
            .clip(RoundedCornerShape(GroupCorner))
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        OnGroupSurface { Column { content() } }
    }
}

/**
 * Zeilen färben ihren Hintergrund mit `surface` (`ListItem` u. a.); auf der Gruppe gilt dafür die Gruppenfläche. So müssen
 * die vielen Zeilen-Bausteine keine Farben mitbekommen.
 */
@Composable
private fun OnGroupSurface(content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val onGroup = remember(scheme) { scheme.copy(surface = scheme.surfaceContainer) }
    MaterialTheme(colorScheme = onGroup, content = content)
}

/** Wie `items`, aber alle Einträge bilden zusammen eine Gruppe ([GroupedItem]). */
inline fun <T> LazyListScope.groupedItems(
    items: List<T>,
    noinline key: ((T) -> Any)? = null,
    crossinline itemContent: @Composable LazyItemScope.(T) -> Unit,
) = groupedItemsIndexed(items, key = key?.let { k -> { _: Int, item: T -> k(item) } }) { _, item -> itemContent(item) }

/** Wie `itemsIndexed`, aber alle Einträge bilden zusammen eine Gruppe ([GroupedItem]). */
inline fun <T> LazyListScope.groupedItemsIndexed(
    items: List<T>,
    noinline key: ((index: Int, T) -> Any)? = null,
    crossinline itemContent: @Composable LazyItemScope.(index: Int, T) -> Unit,
) = itemsIndexed(items, key = key) { index, item ->
    val scope = this
    GroupedItem(index, items.size) { scope.itemContent(index, item) }
}

/** Sammelt die Zeilen eines Abschnitts, siehe [group]. */
class ListGroupScope @PublishedApi internal constructor() {
    @PublishedApi
    internal val rows = mutableListOf<Pair<Any, @Composable () -> Unit>>()

    fun row(key: Any, content: @Composable () -> Unit) {
        rows += key to content
    }
}

/**
 * Abschnitt einer Liste: Überschrift [title] (Schlüssel [key]), darunter die Zeilen aus [build] als eine Gruppe. Bedingte
 * Zeilen (`if`, Schleifen) sind erlaubt; ohne Zeilen entfällt auch die Überschrift.
 */
fun LazyListScope.group(key: Any, @StringRes title: Int? = null, build: ListGroupScope.() -> Unit) {
    val rows = ListGroupScope().apply(build).rows
    if (rows.isEmpty()) return
    if (title != null) item(key = key) { SectionHeader(stringResource(title)) }
    rows.forEachIndexed { index, (rowKey, content) ->
        item(key = rowKey) { GroupedItem(index, rows.size) { content() } }
    }
}
