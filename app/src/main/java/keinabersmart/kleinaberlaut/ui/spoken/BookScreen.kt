package keinabersmart.kleinaberlaut.ui.spoken

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.data.spoken.BookRepository
import keinabersmart.kleinaberlaut.core.model.Book
import keinabersmart.kleinaberlaut.core.model.BookChapter
import keinabersmart.kleinaberlaut.core.model.BookProgress
import keinabersmart.kleinaberlaut.core.model.BookWithProgress
import keinabersmart.kleinaberlaut.ui.components.TwoColumnMinWidth
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import keinabersmart.kleinaberlaut.core.designsystem.R as DesignR

@HiltViewModel(assistedFactory = BookViewModel.Factory::class)
class BookViewModel @AssistedInject constructor(
    @Assisted private val bookId: Long,
    books: BookRepository,
    private val launcher: BookLauncher,
) : ViewModel() {

    val book: StateFlow<BookWithProgress?> = books.book(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val chapters: StateFlow<List<BookChapter>> = books.chapters(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun play() = launcher.play(bookId)

    fun playFrom(positionMs: Long) = launcher.play(bookId, positionMs)

    @AssistedFactory
    interface Factory {
        fun create(bookId: Long): BookViewModel
    }
}

/** Buchseite: Beschreibung, Fortschritt, Abspielen und Kapitelliste (Konzept 12.5). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(
    bookId: Long,
    onBack: () -> Unit,
    isCurrent: Boolean,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BookViewModel = hiltViewModel<BookViewModel, BookViewModel.Factory> { it.create(bookId) },
) {
    val item by viewModel.book.collectAsStateWithLifecycle()
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()
    val book = item?.book
    val progress = item?.progress

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(book?.title.orEmpty(), maxLines = 1) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(painterResource(DesignR.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                }
            },
        )
        if (book == null) return@Column
        val header = @Composable { BookHeader(book, progress, onPlay = if (isCurrent) onOpenPlayer else viewModel::play) }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth >= TwoColumnMinWidth) {
                // Breites Fenster: Cover und Abspielen links, Beschreibung und Kapitel rechts daneben.
                val headerWidth = maxWidth * 0.4f
                Row(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .width(headerWidth)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 24.dp),
                    ) { header() }
                    LazyColumn(Modifier.weight(1f).fillMaxHeight()) {
                        bookDetailItems(book, chapters, onChapter = viewModel::playFrom)
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    item(key = "header") { header() }
                    bookDetailItems(book, chapters, onChapter = viewModel::playFrom)
                }
            }
        }
    }
}

/** Cover, Titel, Autor, Dauer, Fortschritt und Abspielen. */
@Composable
private fun BookHeader(book: Book, progress: BookProgress?, onPlay: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BookArt(book.id, Modifier.widthIn(max = 280.dp).fillMaxWidth())
        Text(
            book.title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(book.author, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (book.narrator.isNotBlank()) {
            Text(book.narrator, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Podcast-Feeds nennen nicht immer eine Dauer.
        if (book.durationMs > 0) Text(formatDuration(book.durationMs), style = MaterialTheme.typography.bodyMedium)
        if (progress != null && progress.positionMs > 0) {
            LinearProgressIndicator(
                progress = { if (progress.finished) 1f else progress.fraction },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                drawStopIndicator = {},
            )
            Text(
                if (progress.finished) {
                    stringResource(R.string.spoken_finished)
                } else {
                    stringResource(R.string.spoken_percent_listened, (progress.fraction * 100).toInt())
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilledTonalButton(onClick = onPlay, modifier = Modifier.padding(top = 8.dp)) {
            Icon(painterResource(DesignR.drawable.ic_play), contentDescription = null)
            val label = when {
                progress?.finished == true -> R.string.spoken_play_again
                progress != null && progress.positionMs > 0 -> R.string.spoken_resume
                else -> R.string.spoken_play
            }
            Text(stringResource(label), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** Beschreibung und Kapitelliste; ein Tipp auf ein Kapitel spielt ab dessen Anfang. */
private fun LazyListScope.bookDetailItems(book: Book, chapters: List<BookChapter>, onChapter: (Long) -> Unit) {
    if (book.description.isNotBlank()) {
        item(key = "description") {
            Text(
                book.description,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
            )
        }
    }
    if (chapters.isNotEmpty()) {
        item(key = "chapters-header") {
            Text(
                stringResource(R.string.spoken_chapters),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
            )
        }
        itemsIndexed(chapters, key = { _, chapter -> chapter.index }) { _, chapter ->
            ListItem(
                headlineContent = { Text(chapterTitle(chapter)) },
                trailingContent = { Text(formatDuration(chapter.durationMs), style = MaterialTheme.typography.bodySmall) },
                modifier = Modifier.clickable { onChapter(chapter.startMs) },
            )
        }
    }
}

@Composable
internal fun chapterTitle(chapter: BookChapter): String =
    chapter.title.ifBlank { stringResource(R.string.spoken_chapter_n, chapter.index + 1) }
