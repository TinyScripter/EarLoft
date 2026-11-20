package de.kleinabersmart.earloft.ui.components

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

/** Distance of a group from the screen edge; headers ([SectionHeader]) align with the group's content. */
val GroupMargin = 8.dp

private val GroupCorner = 20.dp

/**
 * Entry [index] of [count] of a list group: together the entries form one surface with rounded corners at the top of the first
 * and the bottom of the last. Rows inside (e.g. `ListItem`) sit on this surface without specifying their own colors.
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

/** Fixed (non-scrolling) group: all rows from [content] on one surface as with [GroupedItem]. */
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
 * Rows paint their background with `surface` (`ListItem` etc.); within the group the group surface is used for that. That way
 * the many row building blocks don't need colors passed in.
 */
@Composable
private fun OnGroupSurface(content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val onGroup = remember(scheme) { scheme.copy(surface = scheme.surfaceContainer) }
    MaterialTheme(colorScheme = onGroup, content = content)
}

/** Like `items`, but all entries together form one group ([GroupedItem]). */
inline fun <T> LazyListScope.groupedItems(
    items: List<T>,
    noinline key: ((T) -> Any)? = null,
    crossinline itemContent: @Composable LazyItemScope.(T) -> Unit,
) = groupedItemsIndexed(items, key = key?.let { k -> { _: Int, item: T -> k(item) } }) { _, item -> itemContent(item) }

/** Like `itemsIndexed`, but all entries together form one group ([GroupedItem]). */
inline fun <T> LazyListScope.groupedItemsIndexed(
    items: List<T>,
    noinline key: ((index: Int, T) -> Any)? = null,
    crossinline itemContent: @Composable LazyItemScope.(index: Int, T) -> Unit,
) = itemsIndexed(items, key = key) { index, item ->
    val scope = this
    GroupedItem(index, items.size) { scope.itemContent(index, item) }
}

/**
 * Like `items`, but each entry is its own card (a [GroupedItem] of one) instead of part of a shared group; the gap between the
 * cards comes from the list's `verticalArrangement` (e.g. `Arrangement.spacedBy(CardGap)`).
 */
inline fun <T> LazyListScope.cardItems(
    items: List<T>,
    noinline key: ((T) -> Any)? = null,
    crossinline itemContent: @Composable LazyItemScope.(T) -> Unit,
) = items(items.size, key = key?.let { k -> { index: Int -> k(items[index]) } }) { index ->
    val scope = this
    GroupedItem(0, 1) { scope.itemContent(items[index]) }
}

/** Gap between the cards of [cardItems]. */
val CardGap = 6.dp

/** Collects the rows of a section, see [group]. */
class ListGroupScope @PublishedApi internal constructor() {
    @PublishedApi
    internal val rows = mutableListOf<Pair<Any, @Composable () -> Unit>>()

    fun row(key: Any, content: @Composable () -> Unit) {
        rows += key to content
    }
}

/**
 * Section of a list: header [title] (key [key]), below it the rows from [build] as one group. Conditional
 * rows (`if`, loops) are allowed; without rows the header is omitted too.
 */
fun LazyListScope.group(key: Any, @StringRes title: Int? = null, build: ListGroupScope.() -> Unit) {
    val text: (@Composable () -> String)? = title?.let { id -> { stringResource(id) } }
    group(key, text, build)
}

/** Like [group], with a header that is only created while drawing (e.g. with a count). */
fun LazyListScope.group(key: Any, title: (@Composable () -> String)?, build: ListGroupScope.() -> Unit) {
    val rows = ListGroupScope().apply(build).rows
    if (rows.isEmpty()) return
    if (title != null) item(key = key) { SectionHeader(title()) }
    rows.forEachIndexed { index, (rowKey, content) ->
        item(key = rowKey) { GroupedItem(index, rows.size) { content() } }
    }
}
