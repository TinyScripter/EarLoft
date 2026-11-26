package de.kleinabersmart.earloft.ui.spoken

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SnapshotMutationPolicy
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.cast.CastButton
import de.kleinabersmart.earloft.cast.rememberCastDeviceName
import de.kleinabersmart.earloft.ui.theme.PlayerColorTheme
import de.kleinabersmart.earloft.core.model.Book
import de.kleinabersmart.earloft.core.model.BookKind
import androidx.compose.ui.text.TextStyle
import de.kleinabersmart.earloft.core.model.BookTimeline
import de.kleinabersmart.earloft.core.model.EffectLayers
import de.kleinabersmart.earloft.core.model.SpokenEffects
import de.kleinabersmart.earloft.core.playback.PlayerState
import de.kleinabersmart.earloft.ui.components.FastScrollLazyColumn
import de.kleinabersmart.earloft.ui.components.FitSquare
import de.kleinabersmart.earloft.ui.components.LocalShareController
import de.kleinabersmart.earloft.core.data.share.SharePosition
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableLongStateOf
import de.kleinabersmart.earloft.ui.components.WatchTarget
import de.kleinabersmart.earloft.ui.components.WatchToggleButton
import de.kleinabersmart.earloft.core.data.share.ShareTarget
import de.kleinabersmart.earloft.core.data.spoken.SeekSteps
import de.kleinabersmart.earloft.ui.components.TwoColumnMinWidth
import de.kleinabersmart.earloft.ui.player.CoverThenCentered
import de.kleinabersmart.earloft.ui.player.PlayerTopBar
import de.kleinabersmart.earloft.ui.player.ShortHeight
import de.kleinabersmart.earloft.ui.player.SleepTimerLine
import de.kleinabersmart.earloft.ui.player.SleepTimerSheet
import de.kleinabersmart.earloft.ui.player.sleepTimerEnd
import de.kleinabersmart.earloft.ui.player.ToggleIconButton
import de.kleinabersmart.earloft.ui.player.playPauseIcon
import de.kleinabersmart.earloft.ui.player.playPauseLabel
import de.kleinabersmart.earloft.ui.player.rememberPlaybackPosition
import kotlinx.coroutines.flow.Flow
import de.kleinabersmart.earloft.core.designsystem.R as DesignR
import de.kleinabersmart.earloft.core.playback.R as PlaybackR

/** Now-playing view for audiobooks: chapters, remaining times, seeking (configurable), speed (concept 12.5). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookPlayerScreen(
    onClose: () -> Unit,
    onEpisodeClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BookPlayerViewModel = hiltViewModel(),
) {
    val seedColor by viewModel.seedColor.collectAsStateWithLifecycle()
    PlayerColorTheme(seedColor) {
        Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BookPlayerContent(onClose, onEpisodeClick, viewModel = viewModel)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookPlayerContent(
    onClose: () -> Unit,
    onEpisodeClick: (Long) -> Unit,
    viewModel: BookPlayerViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val timeline by viewModel.timeline.collectAsStateWithLifecycle()
    val sleepRemaining by viewModel.sleepTimerRemainingMs.collectAsStateWithLifecycle()
    val sleepToEnd by viewModel.sleepTimerToEnd.collectAsStateWithLifecycle()
    val episode by viewModel.isEpisode.collectAsStateWithLifecycle()
    val hasNextEpisode by viewModel.hasNextEpisode.collectAsStateWithLifecycle()
    val effectLayers by viewModel.effectLayers.collectAsStateWithLifecycle()
    val bookWithProgress by viewModel.book.collectAsStateWithLifecycle()
    val hasTranscript by viewModel.hasTranscript.collectAsStateWithLifecycle()
    val transcript by viewModel.transcript.collectAsStateWithLifecycle()
    val nextInSeries by viewModel.nextInSeries.collectAsStateWithLifecycle()
    val entry = state.spoken
    val seek = entry?.seek ?: SeekSteps.DEFAULT
    // The position in the book needs the timeline; once it is loaded, read it again once (also when paused).
    // On each tick only BookProgress reads it; the rest of the player depends only on the current chapter.
    val position = rememberPlaybackPosition(state.isPlaying, viewModel.positionJumps, entry, timeline) { viewModel.bookPositionMs() }
    val dragging = remember { mutableStateOf<Float?>(null) }
    val chapterIndex by remember(timeline) {
        derivedStateOf { timeline?.chapterAt(dragging.value?.toLong() ?: position.value)?.index }
    }
    var showSpeed by rememberSaveable { mutableStateOf(false) }
    var showSleep by rememberSaveable { mutableStateOf(false) }
    var showChapters by rememberSaveable { mutableStateOf(false) }
    var showBookmarks by rememberSaveable { mutableStateOf(false) }
    val resources = LocalResources.current
    val bookmarksViewModel: BookmarksViewModel = hiltViewModel()
    // Bookmark at the position currently playing (while dragging the slider: there), with chapter name and time as title.
    val addBookmark: () -> Unit = {
        val at = dragging.value?.toLong() ?: viewModel.bookPositionMs()
        entry?.let { bookmarksViewModel.add(it.bookId, at, bookmarkTitle(resources, timeline, at)) }
    }
    var showQueue by rememberSaveable { mutableStateOf(false) }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    // Wide: the chapter list or queue replaces the description in the right panel.
    // Sheets, dialogs and the side pane survive a rotation (review4 T44).
    var pane by rememberSaveable { mutableStateOf(SidePane.DESCRIPTION) }
    // If the episode has a transcript, description and transcript appear side by side as tabs (concept 12.5 "Transcripts").
    var notesTab by rememberSaveable { mutableIntStateOf(NOTES_DESCRIPTION) }

    if (entry == null) {
        // The book was finished or removed: go back instead of showing an empty page.
        Box(modifier.fillMaxSize())
        return
    }
    val tl = timeline
    val duration = tl?.durationMs?.takeIf { it > 0 } ?: state.durationMs
    val speed = state.speed
    val speedLabel = stringResource(R.string.spoken_speed_description, formatSpeed(speed))

    val progress: @Composable () -> Unit = {
        BookProgress(
            positionMs = { position.value },
            dragging = { dragging.value },
            onDrag = { dragging.value = it },
            timeline = tl,
            durationMs = duration,
            speed = speed,
            episode = episode,
            onSeek = viewModel::seekToBook,
        )
    }
    val hasChapters = tl?.hasChapters == true
    val sleepEnd = sleepTimerEnd(episode, hasChapters)
    val sleepLine: @Composable () -> Unit = { SleepTimerLine(sleepRemaining, toEnd = sleepEnd.takeIf { sleepToEnd }) }
    // Episodes with chapters: previous/next chapter instead of "Restart" and "Next episode"; in the last chapter "next" leads
    // to the next episode (like the double click on the headset button).
    val lastChapter = hasChapters && chapterIndex != null && chapterIndex == tl.chapters.lastOrNull()?.index
    // If what was selected is missing (book without chapters, book instead of episode), the description is shown on the right again.
    val shownPane = when {
        pane == SidePane.CHAPTERS && hasChapters -> SidePane.CHAPTERS
        pane == SidePane.BOOKMARKS -> SidePane.BOOKMARKS
        pane == SidePane.QUEUE -> SidePane.QUEUE
        else -> SidePane.DESCRIPTION
    }
    // Phone in landscape (little height): smaller play button, so the bar below stays visible without scrolling.
    val shortHeight = !currentWindowAdaptiveInfoV2().windowSizeClass
        .isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND)
    val controls: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().padding(vertical = if (shortHeight) 4.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Every button gets the same amount of space and shrinks if needed; only play keeps its size.
            // Episodes without chapters: restart at the far left, next episode of the queue at the far right.
            if (episode && !hasChapters) {
                ControlCell {
                    IconButton(onClick = viewModel::restartEpisode) {
                        Icon(painterResource(DesignR.drawable.ic_restart), contentDescription = stringResource(R.string.spoken_restart_episode))
                    }
                }
            }
            // Chapter buttons for books and episodes with chapters (at the start of the first chapter "previous" restarts).
            if (hasChapters) {
                ControlCell {
                    IconButton(onClick = viewModel::previousChapter) {
                        Icon(painterResource(DesignR.drawable.ic_skip_previous), contentDescription = stringResource(R.string.spoken_previous_chapter))
                    }
                }
            }
            ControlCell { SeekBackButton(seek.backSeconds, viewModel::skipBack) }
            FilledIconButton(onClick = viewModel::togglePlayPause, modifier = Modifier.size(if (shortHeight) 56.dp else 72.dp)) {
                Icon(
                    painterResource(playPauseIcon(state.isPlaying)),
                    contentDescription = stringResource(playPauseLabel(state.isPlaying)),
                    modifier = Modifier.size(if (shortHeight) 28.dp else 36.dp),
                )
            }
            ControlCell { SeekForwardButton(seek.forwardSeconds, viewModel::skipForward) }
            if (hasChapters && !(episode && lastChapter)) {
                ControlCell {
                    IconButton(onClick = viewModel::nextChapter, enabled = !lastChapter) {
                        Icon(painterResource(DesignR.drawable.ic_skip_next), contentDescription = stringResource(R.string.spoken_next_chapter))
                    }
                }
            }
            if (episode && (!hasChapters || lastChapter)) {
                ControlCell {
                    IconButton(onClick = viewModel::skipToNextEpisode, enabled = hasNextEpisode) {
                        Icon(painterResource(DesignR.drawable.ic_last_page), contentDescription = stringResource(R.string.spoken_next_episode))
                    }
                }
            }
        }
    }
    val description = bookWithProgress?.book?.description.orEmpty()
    // Timestamps in the show notes seek within the current episode.
    val onTimestamp: ((Long) -> Unit)? = if (episode) viewModel::seekToBook else null
    val sleepActive = sleepRemaining != null
    val castDeviceName = rememberCastDeviceName(state.isRemote)
    val transcriptPane: (@Composable (Modifier) -> Unit)? = if (episode && hasTranscript) {
        { paneModifier ->
            // Loaded only once the transcript is visible.
            LaunchedEffect(Unit) { viewModel.loadTranscript() }
            TranscriptPane(transcript, { position.value }, onSeek = viewModel::seekToBook, onRetry = viewModel::retryTranscript, modifier = paneModifier)
        }
    } else {
        null
    }
    val notes: @Composable (Modifier) -> Unit = { notesModifier ->
        NotesPane(description, duration, onTimestamp, transcriptPane, notesTab, { notesTab = it }, notesModifier)
    }

    BoxWithConstraints(modifier.fillMaxSize().safeDrawingPadding()) {
        // As with music: wide (tablet) cover and controls on the left, description or chapters on the right; phone in landscape
        // the cover on the left and everything else on the right; narrow stacked vertically.
        val short = maxHeight < ShortHeight && maxWidth > maxHeight
        val wide = maxWidth >= TwoColumnMinWidth && !short
        // On small phones the portrait layout gets some bottom margin, otherwise the play button sticks to the screen edge.
        val tight = maxHeight < 700.dp

        // Speed, sleep timer, chapter list, for episodes the queue, when narrow also the description.
        val togglePane: (SidePane) -> Unit = { pane = if (shownPane == it) SidePane.DESCRIPTION else it }
        val actionRow: @Composable () -> Unit = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                CastButton(active = state.isRemote)
                // With trim silence or volume boost a small icon appears next to the speed (not while casting).
                val audioEffects = !state.isRemote && effectLayers?.effects?.hasAudioEffects == true
                val effectsLabel = stringResource(R.string.playback_effects_active)
                TextButton(
                    onClick = { showSpeed = true },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (speed == 1f && !audioEffects) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier.semantics { contentDescription = if (audioEffects) "$speedLabel, $effectsLabel" else speedLabel },
                ) {
                    Text(stringResource(R.string.spoken_speed_value, formatSpeed(speed)))
                    if (audioEffects) {
                        Icon(
                            painterResource(DesignR.drawable.ic_auto_awesome),
                            contentDescription = null,
                            modifier = Modifier.padding(start = 4.dp).size(16.dp),
                        )
                    }
                }
                ToggleIconButton(
                    icon = DesignR.drawable.ic_bedtime,
                    active = sleepActive,
                    label = stringResource(if (sleepActive) R.string.sleep_timer_off else R.string.sleep_timer_title),
                    onClick = { if (sleepActive) viewModel.cancelSleepTimer() else showSleep = true },
                )
                if (hasChapters) {
                    ToggleIconButton(
                        icon = DesignR.drawable.ic_list,
                        active = wide && shownPane == SidePane.CHAPTERS,
                        label = stringResource(R.string.spoken_chapters),
                        onClick = { if (wide) togglePane(SidePane.CHAPTERS) else showChapters = true },
                    )
                }
                ToggleIconButton(
                    icon = DesignR.drawable.ic_bookmark,
                    active = wide && shownPane == SidePane.BOOKMARKS,
                    label = stringResource(R.string.spoken_bookmarks),
                    onClick = { if (wide) togglePane(SidePane.BOOKMARKS) else showBookmarks = true },
                )
                // The podcast queue for episodes, "Continue listening" of the books for books.
                ToggleIconButton(
                    icon = DesignR.drawable.ic_queue_music,
                    active = wide && shownPane == SidePane.QUEUE,
                    label = stringResource(R.string.player_open_queue),
                    onClick = { if (wide) togglePane(SidePane.QUEUE) else showQueue = true },
                )
                if (wide) {
                    // The description is already on the right; the button only brings it back from behind chapters or queue.
                    ToggleIconButton(
                        icon = DesignR.drawable.ic_info,
                        active = shownPane == SidePane.DESCRIPTION,
                        label = stringResource(R.string.spoken_description),
                        onClick = { pane = SidePane.DESCRIPTION },
                    )
                } else {
                    ToggleIconButton(
                        icon = DesignR.drawable.ic_info,
                        active = false,
                        label = stringResource(R.string.spoken_description),
                        onClick = { showInfo = true },
                    )
                }
            }
        }
        val titleBlock: @Composable () -> Unit = {
            Text(
                entry.title,
                style = if (tight) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            if (entry.author.isNotBlank()) {
                Text(
                    entry.author,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            EpisodeDateLine(bookWithProgress?.book, MaterialTheme.typography.bodyMedium)
            if (castDeviceName != null) {
                Text(
                    stringResource(R.string.cast_playing_on, castDeviceName),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(if (tight) 8.dp else 16.dp))
        }
        val titleAndControls: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                titleBlock()
                progress()
                controls()
            }
        }
        val coverShape = MaterialTheme.shapes.extraLarge
        // Episodes: the current chapter's image lies over the cover; if the chapter has a web page, a tap opens it.
        val currentChapter = tl?.chapters?.firstOrNull { it.index == chapterIndex }
        val openLink = rememberOpenLink()
        val chapterLinkLabel = stringResource(R.string.spoken_chapter_link)
        val art: @Composable (Modifier) -> Unit = { artModifier ->
            val link = currentChapter?.url
            // Finished: at the bottom of the cover the next volume of the series (books_improvements M2), starts only on tap.
            // Square like the cover itself, so the card sits on it and not next to it.
            Box(artModifier.aspectRatio(1f)) {
                BookArt(
                    entry.bookId,
                    if (link != null) Modifier.fillMaxSize().clickable(onClickLabel = chapterLinkLabel) { openLink(link) } else Modifier.fillMaxSize(),
                    coverShape,
                    chapterImage = currentChapter?.imageUrl,
                )
                nextInSeries?.let { (next, volume) ->
                    NextVolumeCard(
                        next.book.id,
                        next.book.title,
                        volume,
                        onPlay = { viewModel.playBook(next.book.id) },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                    )
                }
            }
        }

        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PlayerTopBar(
                closeLabel = stringResource(R.string.player_close),
                onClose = onClose,
            ) {
                // Star of an episode as on its page (nothing for books and trial subscriptions).
                bookWithProgress?.let { EpisodeStarButton(it) }
                bookWithProgress?.book?.let { book ->
                    // As in the menu: episodes from the device have no public address.
                    if (book.kind == BookKind.BOOK || !book.isLocal) {
                        PlayerShareButton(book.id) { dragging.value?.toLong() ?: viewModel.bookPositionMs() }
                    }
                    // As on the book/episode page to the right of share; invisible without a watch.
                    WatchToggleButton(WatchTarget.Spoken(book.id))
                }
                // Mark as played (for episodes also "and play next"), queue, reset position.
                bookWithProgress?.let { EpisodeMenuButton(it) }
            }
            if (short) {
                // Cover on the left with the buttons below, title and controls on the right.
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight().padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            art(Modifier.fillMaxHeight())
                        }
                        actionRow()
                        sleepLine()
                    }
                    // Without scrolling: the title gets the space left over by slider and buttons, and shrinks for it.
                    Column(
                        Modifier.weight(1f).fillMaxHeight().padding(bottom = 8.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.weight(1f, fill = false).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                entry.title,
                                style = MaterialTheme.typography.titleLarge,
                                autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = MaterialTheme.typography.titleLarge.fontSize),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }
                        if (entry.author.isNotBlank()) {
                            Text(
                                entry.author,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        EpisodeDateLine(bookWithProgress?.book, MaterialTheme.typography.bodySmall)
                        if (castDeviceName != null) {
                            Text(
                                stringResource(R.string.cast_playing_on, castDeviceName),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        progress()
                        controls()
                    }
                }
            } else if (wide) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Column(
                        Modifier.weight(1f).fillMaxHeight().padding(bottom = 16.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Without fillMaxWidth, so the cover shrinks when height is limited instead of covering the buttons.
                        art(Modifier.weight(1f, fill = false).widthIn(max = 480.dp))
                        actionRow()
                        sleepLine()
                        Spacer(Modifier.height(8.dp))
                        titleAndControls()
                    }
                    Surface(
                        modifier = Modifier.weight(1f).fillMaxHeight().padding(bottom = 16.dp),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        when {
                            shownPane == SidePane.CHAPTERS && tl != null ->
                                ChapterList(tl, chapterIndex, onChapter = viewModel::seekToBook, modifier = Modifier.fillMaxSize().padding(vertical = 8.dp))
                            shownPane == SidePane.BOOKMARKS ->
                                BookmarkList(
                                    entry.bookId,
                                    onAdd = addBookmark,
                                    onJump = viewModel::seekToBook,
                                    modifier = Modifier.fillMaxSize().padding(vertical = 8.dp),
                                    viewModel = bookmarksViewModel,
                                )
                            shownPane == SidePane.QUEUE -> if (episode) {
                                PodcastQueueList(
                                    onEpisodeClick,
                                    Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(vertical = 8.dp),
                                    transparent = true,
                                )
                            } else {
                                BookQueueList(
                                    onEpisodeClick,
                                    Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(vertical = 8.dp),
                                    transparent = true,
                                )
                            }
                            else -> notes(Modifier.fillMaxSize())
                        }
                    }
                }
            } else {
                // Cover at the top with the buttons below; title and controls centered in the remaining space.
                Column(
                    Modifier.weight(1f).widthIn(max = 560.dp).fillMaxWidth().padding(bottom = if (tight) 16.dp else 0.dp),
                    verticalArrangement = CoverThenCentered,
                ) {
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        // Square in the remaining space; a cover that only grows by aspect ratio would otherwise overlap the header and button row.
                        FitSquare(
                            Modifier.weight(1f, fill = false).fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                            maxSide = 420.dp,
                        ) { fit ->
                            art(fit)
                        }
                        actionRow()
                        sleepLine()
                    }
                    titleAndControls()
                }
            }
        }
    }

    if (showInfo) {
        // With a transcript, full height right away: it is long, and the search needs space above the keyboard.
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = transcriptPane != null)
        ModalBottomSheet(onDismissRequest = { showInfo = false }, sheetState = sheetState) {
            if (transcriptPane != null) {
                notes(Modifier.fillMaxSize().navigationBarsPadding().imePadding())
            } else {
                DescriptionPane(description, Modifier.fillMaxWidth().navigationBarsPadding(), duration, onTimestamp)
            }
        }
    }

    if (showSpeed) {
        SpeedDialog(
            speed = speed,
            layers = effectLayers,
            episode = episode,
            remote = state.isRemote,
            onSpeed = viewModel::setSpeed,
            onSkipSilence = viewModel::setSkipSilence,
            onVolumeBoost = viewModel::setVolumeBoost,
            onRememberAsDefault = viewModel::rememberEffectsAsDefault,
            onDismiss = { showSpeed = false },
        )
    }
    if (showSleep) {
        SleepTimerSheet(
            onStart = viewModel::startSleepTimer,
            onDismiss = { showSleep = false },
            toEnd = sleepEnd,
            onStartToEnd = viewModel::startSleepTimerToEnd,
        )
    }
    if (showChapters && tl != null) {
        ChapterSheet(
            timeline = tl,
            currentIndex = chapterIndex,
            onChapter = { viewModel.seekToBook(it) },
            onDismiss = { showChapters = false },
        )
    }
    if (showBookmarks) {
        ModalBottomSheet(onDismissRequest = { showBookmarks = false }) {
            BookmarkList(
                entry.bookId,
                onAdd = addBookmark,
                onJump = {
                    viewModel.seekToBook(it)
                    showBookmarks = false
                },
                modifier = Modifier.navigationBarsPadding(),
                viewModel = bookmarksViewModel,
            )
        }
    }
    if (showQueue) {
        ModalBottomSheet(onDismissRequest = { showQueue = false }) {
            val open: (Long) -> Unit = {
                showQueue = false
                onEpisodeClick(it)
            }
            if (episode) {
                PodcastQueueList(onEpisodeClick = open, modifier = Modifier.navigationBarsPadding(), transparent = true)
            } else {
                BookQueueList(onBookClick = open, modifier = Modifier.navigationBarsPadding(), transparent = true)
            }
        }
    }
}

/** What is shown in the right panel when wide. */
private enum class SidePane { DESCRIPTION, CHAPTERS, BOOKMARKS, QUEUE }

private const val NOTES_DESCRIPTION = 0
private const val NOTES_TRANSCRIPT = 1

/** Card over the cover of a finished book: next volume of the series with play. */
@Composable
private fun NextVolumeCard(bookId: Long, title: String, volume: String?, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 4.dp,
    ) {
        Row(Modifier.clickable(onClick = onPlay).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BookArt(bookId, Modifier.size(48.dp), MaterialTheme.shapes.small)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    volume?.let { stringResource(R.string.series_next_volume_number, volumeNumber(it)) } ?: stringResource(R.string.series_next_volume),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
                Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            FilledTonalIconButton(onClick = onPlay) {
                Icon(painterResource(DesignR.drawable.ic_play), contentDescription = stringResource(R.string.spoken_play))
            }
        }
    }
}

/**
 * Description or show notes; if there is a transcript ([transcript]), as tabs "Description" and "Transcript" ([tab]).
 */
@Composable
private fun NotesPane(
    description: String,
    durationMs: Long,
    onTimestamp: ((Long) -> Unit)?,
    transcript: (@Composable (Modifier) -> Unit)?,
    tab: Int,
    onTab: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (transcript == null) {
        DescriptionPane(description, modifier, durationMs, onTimestamp)
        return
    }
    Column(modifier) {
        PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
            Tab(selected = tab == NOTES_DESCRIPTION, onClick = { onTab(NOTES_DESCRIPTION) }, text = { Text(stringResource(R.string.spoken_description)) })
            Tab(selected = tab == NOTES_TRANSCRIPT, onClick = { onTab(NOTES_TRANSCRIPT) }, text = { Text(stringResource(R.string.spoken_transcript)) })
        }
        if (tab == NOTES_TRANSCRIPT) {
            transcript(Modifier.weight(1f).fillMaxWidth())
        } else {
            DescriptionPane(description, Modifier.weight(1f).fillMaxWidth(), durationMs, onTimestamp, title = false)
        }
    }
}

/**
 * The whole book at the top (for seeking), below it only the chapter; played time on the left, remaining time with "-" on the right.
 * The only part of the player that reads the current position, and only when the second changes (that's how fine the times are).
 * [dragging] is the value of the book slider while dragging; it lives with the caller because the highlighted chapter follows it.
 */
@Composable
private fun BookProgress(
    positionMs: () -> Long,
    dragging: () -> Float?,
    onDrag: (Float?) -> Unit,
    timeline: BookTimeline?,
    durationMs: Long,
    speed: Float,
    episode: Boolean,
    onSeek: (Long) -> Unit,
) {
    // Dragging the chapter bar: offset from the chapter start.
    var chapterDragging by remember { mutableStateOf<Float?>(null) }
    // Stay precise (jumps to a chapter start), but only recompose at the next second.
    val played by remember { derivedStateOf(SameSecond) { positionMs() } }
    val base = dragging()?.toLong() ?: played
    val chapter = timeline?.chapterAt(base)
    val shown = chapterDragging?.let { (chapter?.startMs ?: 0) + it.toLong() } ?: base
    val remainingTotal = timeline?.remainingBookMs(shown, speed) ?: ((durationMs - shown).coerceAtLeast(0) / speed).toLong()

    val positionLabel = stringResource(if (episode) R.string.spoken_position_episode else R.string.spoken_position)
    val positionState = stringResource(R.string.spoken_position_state, formatClock(shown), formatClock(durationMs))
    Slider(
        value = shown.coerceIn(0, durationMs.coerceAtLeast(1)).toFloat(),
        onValueChange = onDrag,
        onValueChangeFinished = {
            dragging()?.let { onSeek(it.toLong()) }
            onDrag(null)
        },
        valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(),
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = positionLabel
                stateDescription = positionState
            },
    )
    // Episodes only have this one slider; a chapter name then sits between the times.
    TimeRow(
        elapsed = formatClock(shown),
        remaining = "-" + formatClock(remainingTotal),
        center = if (episode && chapter != null) chapterTitle(chapter) else null,
    )
    // Only for books with chapters.
    val chapterRemaining = timeline?.remainingChapterMs(shown, speed)
    if (!episode && chapter != null && chapterRemaining != null) {
        // A broken chapter (end before start) would make coerceIn throw; one of length 0 must not jump into the previous one
        // (review G59).
        val chapterLength = chapter.durationMs.coerceAtLeast(1)
        val chapterElapsed = (shown - chapter.startMs).coerceIn(0, chapterLength)
        val chapterLabel = stringResource(R.string.spoken_position_chapter)
        val chapterState = stringResource(R.string.spoken_position_state, formatClock(chapterElapsed), formatClock(chapterLength))
        Slider(
            value = chapterElapsed.toFloat(),
            onValueChange = { chapterDragging = it },
            onValueChangeFinished = {
                // Stay just before the end so the jump doesn't already land in the next chapter.
                chapterDragging?.let { onSeek(chapter.startMs + it.toLong().coerceAtMost(chapterLength - 1)) }
                chapterDragging = null
            },
            valueRange = 0f..chapterLength.toFloat(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .semantics {
                    contentDescription = chapterLabel
                    stateDescription = chapterState
                },
        )
        TimeRow(
            elapsed = formatClock(chapterElapsed),
            remaining = "-" + formatClock(chapterRemaining),
            center = chapterTitle(chapter),
        )
    }
}

/** Positions within the same second count as equal; the display has one-second resolution. */
private object SameSecond : SnapshotMutationPolicy<Long> {
    override fun equivalent(a: Long, b: Long): Boolean = a / 1000 == b / 1000
}

/**
 * Played time on the left, remaining time on the right, optionally a text exactly in the middle (chapter name, i.e. above the play button).
 * The middle doesn't depend on the width of the times: the text gets at most as much space as remains free on both sides
 * next to the wider time.
 */
@Composable
private fun TimeRow(elapsed: String, remaining: String, center: String? = null) {
    Layout(
        content = {
            Text(elapsed, style = MaterialTheme.typography.bodySmall)
            Text(remaining, style = MaterialTheme.typography.bodySmall)
            if (center != null) {
                Text(
                    center,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val start = measurables[0].measure(loose)
        val end = measurables[1].measure(loose)
        val gap = 8.dp.roundToPx()
        val side = maxOf(start.width, end.width)
        val middle = measurables.getOrNull(2)?.measure(loose.copy(maxWidth = (constraints.maxWidth - 2 * (side + gap)).coerceAtLeast(0)))
        val height = maxOf(start.height, end.height, middle?.height ?: 0)
        layout(constraints.maxWidth, height) {
            start.placeRelative(0, (height - start.height) / 2)
            end.placeRelative(constraints.maxWidth - end.width, (height - end.height) / 2)
            middle?.placeRelative((constraints.maxWidth - middle.width) / 2, (height - middle.height) / 2)
        }
    }
}

/** Equal-width cell in the playback bar; a larger button is limited to the cell width. */
@Composable
private fun RowScope.ControlCell(content: @Composable () -> Unit) {
    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { content() }
}

/** Skip back or forward with the number of seconds in the arrow. */
@Composable
private fun SeekBackButton(seconds: Int, onClick: () -> Unit) =
    SeekButton(DesignR.drawable.ic_replay, seconds, pluralStringResource(PlaybackR.plurals.spoken_seek_back, seconds, seconds), onClick)

@Composable
private fun SeekForwardButton(seconds: Int, onClick: () -> Unit) =
    SeekButton(DesignR.drawable.ic_forward, seconds, pluralStringResource(PlaybackR.plurals.spoken_seek_forward, seconds, seconds), onClick)

@Composable
private fun SeekButton(icon: Int, seconds: Int, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(56.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = description, modifier = Modifier.size(40.dp))
            // The number is already in the icon's description; TalkBack shouldn't read it out again.
            Text(seconds.toString(), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 3.dp).clearAndSetSemantics {})
        }
    }
}

/**
 * Speed and effects of the book or episode (concept 12.4). Everything applies only here at first; "For all books" or "For
 * this podcast" makes it the default. While casting there is only the speed.
 */
@Composable
private fun SpeedDialog(
    speed: Float,
    layers: EffectLayers?,
    episode: Boolean,
    remote: Boolean,
    onSpeed: (Float) -> Unit,
    onSkipSilence: (Boolean) -> Unit,
    onVolumeBoost: (Int) -> Unit,
    onRememberAsDefault: () -> Unit,
    onDismiss: () -> Unit,
) {
    // While dragging, the slider counts; the player catches up via the database.
    var dragged by remember { mutableStateOf<Float?>(null) }
    val shown = dragged ?: speed
    val effects = layers?.effects
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_playback)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.spoken_speed_value, formatSpeed(shown)),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Slider(
                    value = shown,
                    onValueChange = {
                        // Every pointer event lands here; only a new step is written (review2 J56).
                        val snapped = snapSpeed(it)
                        if (snapped != dragged) {
                            dragged = snapped
                            onSpeed(snapped)
                        }
                    },
                    onValueChangeFinished = { dragged = null },
                    valueRange = MIN_SPEED..MAX_SPEED,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1f, 1.25f, 1.5f, 2f).forEach { preset ->
                        FilterChip(
                            selected = snapSpeed(shown) == preset,
                            onClick = { onSpeed(preset) },
                            label = { Text(stringResource(R.string.spoken_speed_value, formatSpeed(preset))) },
                        )
                    }
                }
                // The receiver limits the speed without a word (review4 T12); back on the phone the chosen one applies again.
                if (remote && shown > CAST_MAX_SPEED) {
                    Text(
                        stringResource(R.string.playback_speed_cast_limit),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (effects != null) {
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.playback_skip_silence)) },
                        supportingContent = {
                            Text(stringResource(if (remote) R.string.playback_effects_cast else R.string.playback_skip_silence_summary))
                        },
                        trailingContent = { Switch(checked = effects.skipSilence, onCheckedChange = null, enabled = !remote) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.toggleable(
                            value = effects.skipSilence,
                            enabled = !remote,
                            role = Role.Switch,
                            onValueChange = onSkipSilence,
                        ),
                    )
                    Text(
                        stringResource(R.string.playback_volume_boost),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp),
                    )
                    val boostLabel = stringResource(R.string.playback_volume_boost)
                    val choices = SpokenEffects.VOLUME_BOOST_CHOICES
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().semantics { contentDescription = boostLabel }) {
                        choices.forEachIndexed { index, db ->
                            SegmentedButton(
                                selected = effects.volumeBoostDb == db,
                                onClick = { onVolumeBoost(db) },
                                shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                                enabled = !remote,
                                icon = {},
                                label = { Text(volumeBoostLabel(db), maxLines = 1) },
                            )
                        }
                    }
                    // Custom values: hint and the way to make them the default.
                    if (!layers.own.isEmpty) {
                        Text(
                            stringResource(if (episode) R.string.playback_own_episode else R.string.playback_own_book),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                }
            }
        },
        dismissButton = if (layers != null && !layers.own.isEmpty) {
            {
                TextButton(onClick = onRememberAsDefault) {
                    Text(
                        stringResource(
                            when {
                                !episode -> R.string.playback_remember_books
                                layers.feed != null -> R.string.playback_remember_podcast
                                else -> R.string.playback_remember_podcasts
                            },
                        ),
                    )
                }
            }
        } else {
            null
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.spoken_close)) } },
    )
}

/** "Off" or "+6 dB". */
@Composable
internal fun volumeBoostLabel(db: Int): String =
    if (db <= 0) stringResource(R.string.playback_volume_boost_off) else stringResource(R.string.playback_volume_boost_value, db)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterSheet(
    timeline: BookTimeline,
    currentIndex: Int?,
    onChapter: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        ChapterList(
            timeline = timeline,
            currentIndex = currentIndex,
            onChapter = {
                onChapter(it)
                onDismiss()
            },
            modifier = Modifier.navigationBarsPadding(),
        )
    }
}

@Composable
private fun ChapterList(
    timeline: BookTimeline,
    currentIndex: Int?,
    onChapter: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The list opens at the current chapter.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = timeline.chapters.indexOfFirst { it.index == currentIndex }.coerceAtLeast(0))
    val withImages = timeline.chapters.any { it.imageUrl != null }
    FastScrollLazyColumn(modifier, state = listState) {
        itemsIndexed(timeline.chapters, key = { _, chapter -> chapter.index }) { _, chapter ->
            ListItem(
                headlineContent = { Text(chapterTitle(chapter), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = if (chapter.unavailable) ({ Text(stringResource(R.string.ard_books_part_gone)) }) else null,
                leadingContent = if (withImages) ({ ChapterImage(chapter.imageUrl) }) else null,
                trailingContent = {
                    ChapterTrailing(chapter.url) { ChapterTimes(chapter.startMs, chapter.durationMs) }
                },
                colors = unavailableChapterColors(
                    chapter,
                    containerColor = if (chapter.index == currentIndex) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                ),
                modifier = Modifier.clickable(enabled = !chapter.unavailable) { onChapter(chapter.startMs) },
            )
        }
    }
}

/** Share in the player: the book or episode, or from the position currently playing (concept 13). */
@Composable
private fun PlayerShareButton(bookId: Long, positionMs: () -> Long) {
    val controller = LocalShareController.current ?: return
    var open by remember { mutableStateOf(false) }
    var at by remember { mutableLongStateOf(0L) }
    Box {
        IconButton(onClick = {
            at = positionMs()
            open = true
        }) {
            Icon(painterResource(DesignR.drawable.ic_share), contentDescription = stringResource(R.string.action_share))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_share)) },
                onClick = {
                    controller.share(ShareTarget.Book(bookId))
                    open = false
                },
            )
            val label = stringResource(R.string.spoken_share_position, formatClock(at))
            DropdownMenuItem(
                text = { Text(stringResource(R.string.spoken_share_from, formatClock(at))) },
                onClick = {
                    controller.share(ShareTarget.Book(bookId, SharePosition(at, label)))
                    open = false
                },
            )
        }
    }
}

/** Description of the book or show notes of the episode; with [onTimestamp] timestamps are tappable. */
@Composable
private fun DescriptionPane(
    description: String,
    modifier: Modifier = Modifier,
    durationMs: Long = 0,
    onTimestamp: ((Long) -> Unit)? = null,
    title: Boolean = true,
) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
        if (title) Text(stringResource(R.string.spoken_description), style = MaterialTheme.typography.titleSmall)
        val formatted = rememberDescription(description, durationMs, onTimestamp)
        Text(
            if (description.isBlank()) AnnotatedString(stringResource(R.string.spoken_no_description)) else formatted,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = if (title) 8.dp else 0.dp),
        )
    }
}

/** Mini player for audiobooks: cover, title, back, play/pause, forward, thin progress bar of the book. */
@Composable
fun SpokenMiniPlayer(
    state: PlayerState,
    fractionProvider: () -> Float,
    positionJumps: Flow<Unit>,
    /** Changes as soon as [fractionProvider] can deliver something new (loaded timeline). */
    refreshKey: Any?,
    onClick: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entry = state.spoken ?: return
    val fraction by rememberPlaybackPosition(state.isPlaying, positionJumps, entry, refreshKey) { (fractionProvider() * 1000).toLong() }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column {
            LinearProgressIndicator(
                progress = { (fraction / 1000f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                drawStopIndicator = {},
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            ) {
                BookArt(entry.bookId, Modifier.size(44.dp), shape = MaterialTheme.shapes.small)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        entry.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                SeekBackButton(entry.seek.backSeconds, onSkipBack)
                IconButton(onClick = onTogglePlayPause) {
                    Icon(painterResource(playPauseIcon(state.isPlaying)), contentDescription = stringResource(playPauseLabel(state.isPlaying)))
                }
                SeekForwardButton(entry.seek.forwardSeconds, onSkipForward)
            }
        }
    }
}

/** Below the podcast name: when the episode was published, with time of day. Books and episodes without a date: nothing. */
@Composable
private fun EpisodeDateLine(book: Book?, style: TextStyle) {
    val publishedAt = book?.takeIf { it.kind == BookKind.EPISODE }?.addedAt?.takeIf { it > 0 } ?: return
    Text(
        formatEpisodeDay(publishedAt, withTime = true),
        style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

private const val MIN_SPEED = 0.5f
private const val MAX_SPEED = 3f

/** Media3's cast player limits the speed to this. */
private const val CAST_MAX_SPEED = 2f
private const val SPEED_STEP = 0.05f

/** Rounds to steps of 0.05 so slider and display match. */
private fun snapSpeed(value: Float): Float = (Math.round(value / SPEED_STEP) * SPEED_STEP).coerceIn(MIN_SPEED, MAX_SPEED)

/** "1", "1,25", "1,5" – without superfluous zeros. */
private fun formatSpeed(speed: Float): String =
    "%.2f".format(snapSpeed(speed)).trimEnd('0').trimEnd(',', '.')

/** The book's fraction for the mini player. */
fun BookPlayerViewModel.bookFraction(): Float {
    val duration = durationMs()
    return if (duration > 0) (bookPositionMs().toFloat() / duration).coerceIn(0f, 1f) else 0f
}
