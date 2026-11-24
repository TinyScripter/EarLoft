package de.kleinabersmart.earloft.core.playback

import android.content.Context
import android.content.Intent
import android.annotation.SuppressLint
import android.content.res.Resources
import android.os.Bundle
import android.util.Log
import android.os.Build
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import de.kleinabersmart.earloft.core.data.podcast.ContinueListening
import de.kleinabersmart.earloft.core.data.repository.FavoritesRepository
import de.kleinabersmart.earloft.core.data.repository.LibraryRepository
import de.kleinabersmart.earloft.core.data.spoken.BookPlaybackSource
import de.kleinabersmart.earloft.core.model.Song
import de.kleinabersmart.earloft.core.model.SpokenMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.guava.future

/** Custom action "Toggle favorite" (heart in Android Auto, notification, watch). */
internal const val ACTION_TOGGLE_FAVORITE = "de.kleinabersmart.earloft.TOGGLE_FAVORITE"

/** Chapter back or forward as a button in the notification and Auto (`books_improvements.md` M4). */
internal const val ACTION_CHAPTER_PREVIOUS = "de.kleinabersmart.earloft.CHAPTER_PREVIOUS"
internal const val ACTION_CHAPTER_NEXT = "de.kleinabersmart.earloft.CHAPTER_NEXT"

/**
 * Does the voice request [EXTRA_VOICE_QUERY] find anything? The app asks before it sets and plays the request: a request
 * without results keeps the queue (review G10), and a "play" right after it would only continue that queue.
 */
internal const val ACTION_VOICE_CHECK = "de.kleinabersmart.earloft.VOICE_CHECK"
internal const val EXTRA_VOICE_QUERY = "query"

/**
 * Browse tree and resolution of media items for all controllers (app, system, watch, Android Auto).
 *
 * The tree's content comes from [BrowseTree] (tabs depending on the enabled areas, at most four). Songs in lists
 * carry their folder in the media ID ([MediaIds.inContext]); if one of them is set alone (tap in Auto),
 * [onSetMediaItems] loads the whole list as the queue. The session prepares a book or episode (`spoken:<id>`)
 * itself, like the app ([startSpoken]).
 *
 * Controllers send items without a playable URI across process boundaries; [onAddMediaItems] and
 * [onSetMediaItems] therefore rebuild them from the database by media ID. Songs that the
 * current player can't play ([SessionPlayerFactory.playableSongIds], e.g. while casting)
 * are dropped.
 */
@OptIn(UnstableApi::class)
internal class LibrarySessionCallback(
    private val context: Context,
    private val libraryRepository: LibraryRepository,
    private val favoritesRepository: FavoritesRepository,
    private val queuePersistence: QueuePersistence,
    private val bookPlaybackSource: BookPlaybackSource,
    private val browseTree: BrowseTree,
    private val continueListening: ContinueListening,
    private val playerFactory: SessionPlayerFactory?,
    private val scope: CoroutineScope,
    /** Headphone and media buttons for books and episodes; `null`: everything as in Media3. */
    private val mediaButtons: SpokenMediaButtons? = null,
    /** Chapter buttons ([ACTION_CHAPTER_NEXT] = `true`); on the main thread. */
    private val onChapterButton: (forward: Boolean) -> Unit = {},
    /** Waits until the service has restored the last queue ([PlaybackService.restoreQueue]). */
    private val awaitRestore: suspend () -> Unit = {},
) : MediaLibrarySession.Callback {

    /**
     * Must complete immediately: for old `MediaBrowser`s (Android Auto, system) Media3 answers `onGetRoot` on the
     * main thread and waits there for this future without a timeout. A `scope.future` on the main dispatcher would never run
     * (app hangs, ANR).
     */
    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
        LibraryResult.ofItem(
            folderMediaItem(MediaIds.ROOT, context.getString(R.string.app_library), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            LibraryParams.Builder().setExtras(rootExtras()).build(),
        ),
    )

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
        // Android Auto remembers lists: folders of an area disabled in the meantime no longer exist.
        if (browseTree.isHidden(parentId)) return@future LibraryResult.ofError(areaDisabled())
        val children = browseTree.children(parentId) ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        val paged = children.drop(page * pageSize).take(pageSize)
        LibraryResult.ofItemList(paged, params)
    }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = scope.future {
        session.notifySearchResultChanged(browser, query, searchResults(query).size, params)
        LibraryResult.ofVoid()
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
        val items = searchResults(query)
        LibraryResult.ofItemList(items.drop(page * pageSize).take(pageSize), params)
    }

    /**
     * Last searched term along with the enabled areas and its results: [onSearch] is immediately followed by
     * [onGetSearchResult], often page by page.
     */
    private var lastSearch: Pair<Pair<String, List<SpokenMode>>, List<MediaItem>>? = null

    /** Runs on the main thread (scope), so [lastSearch] needs no lock. */
    private suspend fun searchResults(query: String): List<MediaItem> {
        val key = query to browseTree.areas()
        lastSearch?.takeIf { it.first == key }?.let { return it.second }
        val items = browseTree.search(query)
        lastSearch = key to items
        return items
    }

    /**
     * Accepts every subscription to a folder that doesn't belong to a disabled area. The default checks via
     * [onGetItem], which only knows songs and books, and thus rejected all folders; Android Auto (compat browser) then never
     * learned of [MediaLibrarySession.notifyChildrenChanged] (areas toggled).
     */
    override fun onSubscribe(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = scope.future {
        if (browseTree.isHidden(parentId)) return@future LibraryResult.ofError(areaDisabled())
        // Like the default: Media3 browsers then fetch the content, old ones get it directly anyway.
        if (browser.controllerVersion != MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION) {
            session.notifyChildrenChanged(browser, parentId, Int.MAX_VALUE, params)
        }
        LibraryResult.ofVoid()
    }

    /** For books and episodes, double click and "next" jump to the next chapter ([SpokenMediaButtons]). */
    override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent): Boolean {
        val event = keyEventOf(intent) ?: return false
        val external = controllerInfo.controllerVersion == MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION
        return mediaButtons?.onMediaButton(event, external) ?: false
    }

    /** Like Media3 (`DefaultActionFactory.getKeyEvent`): broken extras are not a key. */
    private fun keyEventOf(intent: Intent): KeyEvent? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }
    } catch (_: RuntimeException) {
        null
    }

    private fun areaDisabled() = SessionError(SessionError.ERROR_NOT_SUPPORTED, context.getString(R.string.area_disabled))

    /**
     * The service is exported for the system, watch and Android Auto, which makes it reachable for every app. Trusted
     * controllers ([isTrustedController]) get everything; foreign apps may control playback, but may neither browse the
     * library nor replace the queue nor set favorites (those would go to the server via the sync queue).
     */
    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
        val trusted = session.isTrustedController(controller, context.packageName)
        Log.d(TAG, "Controller ${controller.packageName}: ${if (trusted) "trusted" else "playback control only"}")
        if (trusted) grantCoverAccess(controller.packageName)
        val commands = controllerCommands(trusted)
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            .setAvailableSessionCommands(commands.session)
            .setAvailablePlayerCommands(commands.player)
            .build()
    }

    /**
     * The system UI reads the metadata of the platform session directly and loads the cover itself; it only connects
     * ([onConnect]) with the first command (pause, next). That's why it gets the grant right when the service starts.
     * Android discards grants with every app update and every device reboot.
     */
    fun grantSystemUiCoverAccess() {
        systemUiPackage()?.let(::grantCoverAccess)
    }

    /**
     * Package of the system UI from the device configuration (`config_systemUi`, only reachable by name), otherwise the
     * AOSP name. Don't check via the PackageManager: package visibility since API 30 hides it; a wrong name only
     * makes the grant fail ([grantCoverAccess]).
     */
    @SuppressLint("DiscouragedApi")
    private fun systemUiPackage(): String? {
        val system = Resources.getSystem()
        val id = system.getIdentifier("config_systemUi", "string", "android")
        return (if (id != 0) system.getString(id) else null)?.takeIf { it.isNotBlank() } ?: DEFAULT_SYSTEM_UI_PACKAGE
    }

    /**
     * The [CoverProvider] is not exported: trusted controllers get read access to all covers (one
     * prefix grant), so that Android Auto or the system UI can load the `content://` covers of the lists.
     */
    private fun grantCoverAccess(packageName: String) {
        if (packageName == context.packageName || packageName == MediaSession.ControllerInfo.LEGACY_CONTROLLER_PACKAGE_NAME) return
        try {
            context.grantUriPermission(
                packageName,
                CoverProvider.rootUri(context),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cover not granted to $packageName", e)
        }
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> = scope.future {
        when (customCommand.customAction) {
            ACTION_TOGGLE_FAVORITE -> session.player.currentMediaItem?.songId?.let { favoritesRepository.toggleFavorite(it) }
            ACTION_CHAPTER_PREVIOUS -> onChapterButton(false)
            ACTION_CHAPTER_NEXT -> onChapterButton(true)
            ACTION_VOICE_CHECK -> {
                val target = voiceTarget(session.player, args.getString(EXTRA_VOICE_QUERY).orEmpty())
                if (target is VoiceStart.Songs && target.songs.isEmpty()) return@future SessionResult(SessionError.ERROR_BAD_VALUE)
            }
            else -> return@future SessionResult(SessionError.ERROR_NOT_SUPPORTED)
        }
        SessionResult(SessionResult.RESULT_SUCCESS)
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
        if (browseTree.isHidden(mediaId)) return@future LibraryResult.ofError(areaDisabled())
        val songId = MediaIds.songId(mediaId)
        val spokenId = MediaIds.spokenId(mediaId)
        val item = when {
            songId != null -> libraryRepository.songs(listOf(songId)).firstOrNull()?.toMediaItem()
            spokenId != null -> browseTree.spokenItem(spokenId)
            else -> null
        }
        if (item != null) LibraryResult.ofItem(item, null) else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> = scope.future {
        val player = mediaSession.player
        val items = resolve(player, mediaItems).map { it.second }
        val factory = playerFactory ?: return@future items
        if (factory.hasPendingItems()) {
            // There's more behind what the player holds: new titles go to its end.
            factory.appendPending(items)
            return@future emptyList()
        }
        // Whatever no longer fits into the player (cast: at most 50 titles in total), the factory hands over later.
        val room = factory.roomIn(player)
        if (items.size <= room) return@future items
        factory.appendPending(items.drop(room))
        items.take(room)
    }

    /**
     * Like [onAddMediaItems]; if the start title is dropped, playback begins at the next remaining one.
     * If the list is longer than the player allows, a window containing the start title is used ([queueWindow]);
     * the factory hands over the rest later ([SessionPlayerFactory.onQueueReplaced]).
     */
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
        val player = mediaSession.player
        val single = mediaItems.singleOrNull()
        val voiceQuery = single?.takeIf { it.mediaId.isEmpty() }?.requestMetadata?.searchQuery
        val spokenId = single?.let { MediaIds.spokenId(it.mediaId) }
        val (requested, requestedStart) = when {
            spokenId != null -> return@future startSpoken(player, spokenId)
            voiceQuery != null -> when (val target = voiceTarget(player, voiceQuery)) {
                is VoiceStart.Ready -> return@future target.items
                is VoiceStart.Spoken -> return@future startSpoken(player, target.bookId)
                // Nothing found (unknown name, podcast without episodes, empty library): keep what is playing instead of
                // replacing it with an empty queue (review G10).
                is VoiceStart.Songs -> target.songs.ifEmpty { throw UnsupportedOperationException("Nothing found for the voice request") }
                    .map { it.toMediaItem() } to 0
            }
            else -> expandContext(mediaItems, startIndex)
        }
        val resolved = resolve(player, requested)
        if (resolved.isEmpty() && requested.isNotEmpty()) {
            // Nothing playable: keep the queue instead of clearing it.
            throw UnsupportedOperationException("None of the tracks is playable")
        }
        val items = resolved.map { it.second }
        val start = queueStart(resolved.map { it.first }, requestedStart, startPositionMs)
        val maxSize = maxQueueSize(player)
        if (items.size <= maxSize) {
            playerFactory?.onQueueReplaced(emptyList(), emptyList())
            return@future MediaItemsWithStartPosition(items, start.index, start.positionMs)
        }
        val window = queueWindow(items, start.index.takeIf { it != C.INDEX_UNSET } ?: 0, maxSize, player.shuffleModeEnabled)
        playerFactory?.onQueueReplaced(window.before, window.after)
        MediaItemsWithStartPosition(window.items, window.startIndex, start.positionMs)
    }

    /**
     * A single song from a list (Android Auto only sends the media ID) becomes the whole list with this
     * song as start. Everything else stays unchanged.
     */
    private suspend fun expandContext(mediaItems: List<MediaItem>, startIndex: Int): Pair<List<MediaItem>, Int> {
        val item = mediaItems.singleOrNull() ?: return mediaItems to startIndex
        val folderId = MediaIds.contextOf(item.mediaId) ?: return mediaItems to startIndex
        val songs = browseTree.songsOf(folderId) ?: return mediaItems to startIndex
        val index = songs.indexOfFirst { it.id == item.songId }
        if (index < 0) return mediaItems to startIndex
        return songs.map { it.toMediaItem() } to index
    }

    /** How the session starts on "Play …". */
    private sealed interface VoiceStart {
        /** Ready-made queue (resume). */
        class Ready(val items: MediaItemsWithStartPosition) : VoiceStart
        class Spoken(val bookId: Long) : VoiceStart
        class Songs(val songs: List<Song>) : VoiceStart
    }

    /**
     * What plays on "Play …", only from enabled areas. With a term: [BrowseTree.voice]. Without a term it
     * resumes: what is in the player, otherwise the last listened book or episode, otherwise the saved
     * music queue; if there is none of those, [BrowseTree.voiceWithoutQuery].
     */
    private suspend fun voiceTarget(player: Player, query: String): VoiceStart {
        if (query.isNotBlank()) return browseTree.voice(query).toVoiceStart()
        val areas = browseTree.areas()
        if (player.mediaItemCount > 0 && player.currentMediaItem?.area in areas) {
            val items = (0 until player.mediaItemCount).map(player::getMediaItemAt)
            return VoiceStart.Ready(MediaItemsWithStartPosition(items, player.currentMediaItemIndex, player.currentPosition))
        }
        queuePersistence.lastBookId()?.takeIf { browseTree.isSpokenEnabled(it, areas) }?.let { return VoiceStart.Spoken(it) }
        if (SpokenMode.MUSIC in areas) {
            queuePersistence.restore()?.let { restored ->
                player.shuffleModeEnabled = restored.shuffle
                player.repeatMode = restored.repeatMode
                return VoiceStart.Ready(MediaItemsWithStartPosition(restored.items, restored.startIndex, restored.startPositionMs))
            }
        }
        return browseTree.voiceWithoutQuery().toVoiceStart()
    }

    private fun VoiceTarget.toVoiceStart(): VoiceStart = when (this) {
        is VoiceTarget.Spoken -> VoiceStart.Spoken(bookId)
        is VoiceTarget.Songs -> VoiceStart.Songs(songs)
        else -> VoiceStart.Songs(emptyList())
    }

    /**
     * A book or episode from the browse tree or via voice command: like [PlaybackConnection.playBook] in the app
     * (saved position with rewind, speed, without shuffle/repeat, episode at the front of "Continue listening").
     */
    private suspend fun startSpoken(player: Player, bookId: Long): MediaItemsWithStartPosition {
        if (!browseTree.isSpokenEnabled(bookId)) throw UnsupportedOperationException("Area of book $bookId is turned off")
        continueListening.started(bookId)
        val prepared = withContext(Dispatchers.IO) { bookPlaybackSource.prepare(bookId) }
            ?: throw UnsupportedOperationException("Book $bookId not playable")
        val start = prepared.timeline.locate(prepared.startPositionMs)
        playerFactory?.onQueueReplaced(emptyList(), emptyList())
        // Shuffle and repeat go off only once the book is in the player (PlaybackService): here the music is still loaded, and
        // switching them off now would be saved as the music's modes.
        player.setPlaybackSpeed(prepared.speed)
        return MediaItemsWithStartPosition(prepared.toMediaItems(), start.trackIndex, start.offsetMs)
    }

    private fun maxQueueSize(player: Player): Int = playerFactory?.maxQueueSize(player) ?: Int.MAX_VALUE

    /** Rebuilt items along with their position in [mediaItems]; unknown and unplayable songs are missing. */
    private suspend fun resolve(player: Player, mediaItems: List<MediaItem>): List<Pair<Int, MediaItem>> {
        val songIds = mediaItems.mapNotNull { it.songId }.distinct()
        val songs = libraryRepository.songs(songIds).associateBy { it.id }
        val playable = playerFactory?.playableSongIds(player, songs.keys) ?: songs.keys
        return mediaItems.mapIndexedNotNull { index, item ->
            // Audiobooks are already fully built (BookMediaItems) and pass through unchanged.
            if (item.isSpoken) return@mapIndexedNotNull index to item
            item.songId?.takeIf { it in playable }?.let(songs::get)?.let { index to it.toMediaItem() }
        }
    }

    /**
     * [isForPlayback] `false`: the system UI only wants metadata for the resumption notification (e.g. after
     * the device reboots); then the current title suffices, and nothing changes in the player.
     */
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
        // The system UI loads the cover of the resumption card itself; the grant for it was given at the start of the
        // service ([grantSystemUiCoverAccess]).
        if (isForPlayback) {
            // If "play" (headphones, widget, resumption card) comes right after the service starts, its restore is
            // still running. If the restore won, the book is already in the player and has remembered the pause; a
            // second load via prepare() would then rewind twice (prepare applies the rule, the playback
            // afterwards once more). In that case the loaded state stays.
            awaitRestore()
            val player = mediaSession.player
            if (player.mediaItemCount > 0) {
                val items = (0 until player.mediaItemCount).map(player::getMediaItemAt)
                return@future MediaItemsWithStartPosition(items, player.currentMediaItemIndex, player.currentPosition)
            }
        }
        // If a book or episode played last, it is resumed instead of the music queue; not if its
        // area has been disabled in the meantime.
        val areas = browseTree.areas()
        queuePersistence.lastBookId()?.takeIf { browseTree.isSpokenEnabled(it, areas) }?.let { bookId ->
            // Metadata only (system UI): without rewinding and without writing, as at service start.
            val prepared = withContext(Dispatchers.IO) {
                if (isForPlayback) bookPlaybackSource.prepare(bookId) else bookPlaybackSource.restore(bookId)
            }
            if (prepared != null) {
                val start = prepared.timeline.locate(prepared.startPositionMs)
                val items = prepared.toMediaItems()
                if (!isForPlayback) {
                    // As with music: the system UI doesn't know earloft://cover, hence the CoverProvider URI.
                    val current = items[start.trackIndex]
                    val withCover = current.buildUpon()
                        .setMediaMetadata(
                            current.mediaMetadata.buildUpon().setArtworkUri(CoverProvider.bookCoverUri(context, bookId)).build(),
                        )
                        .build()
                    return@future MediaItemsWithStartPosition(listOf(withCover), 0, start.offsetMs)
                }
                mediaSession.player.shuffleModeEnabled = false
                mediaSession.player.repeatMode = Player.REPEAT_MODE_OFF
                mediaSession.player.setPlaybackSpeed(prepared.speed)
                return@future MediaItemsWithStartPosition(items, start.trackIndex, start.offsetMs)
            }
        }
        if (SpokenMode.MUSIC !in areas) throw UnsupportedOperationException("Music is turned off")
        val restored = queuePersistence.restore() ?: throw UnsupportedOperationException("No saved queue")
        if (!isForPlayback) {
            val current = restored.items.getOrNull(restored.startIndex)
                ?: throw UnsupportedOperationException("No saved queue")
            // The system UI loads the cover itself and doesn't know earloft://cover (see CoverProvider).
            val coverUri = current.mediaMetadata.albumIdExtra?.let { CoverProvider.albumCoverUri(context, it) }
            val withCover = current.buildUpon()
                .setMediaMetadata(current.mediaMetadata.buildUpon().setArtworkUri(coverUri).build())
                .build()
            return@future MediaItemsWithStartPosition(listOf(withCover), 0, restored.startPositionMs)
        }
        mediaSession.player.shuffleModeEnabled = restored.shuffle
        mediaSession.player.repeatMode = restored.repeatMode
        MediaItemsWithStartPosition(restored.items, restored.startIndex, restored.startPositionMs)
    }

    private companion object {
        const val TAG = "LibrarySession"
        const val DEFAULT_SYSTEM_UI_PACKAGE = "com.android.systemui"
    }
}

/**
 * The app itself ([ownPackage]), the system (notification, lock screen, resumption after reboot; also apps with media control
 * permission or acting as notification listener, like the watch companion app) and Android Auto/Automotive.
 *
 * Those after the system are recognised by their package name. A Media3 controller only names it itself; if Media3 could not
 * check it against the caller (the package is not visible to the app), any app could claim to be the app itself or Android Auto.
 * Such a name doesn't count. Older controllers (Android Auto, the system UI) connect through the platform, which knows the
 * caller.
 *
 * Known residual risk, accepted on purpose: below Android 13 the platform does not verify the name of a legacy controller
 * either, so an app that impersonates Android Auto there could replace the queue and set favorites, nothing more (see
 * `ideas/konzept_und_architektur.md`, media session). Trusting only [MediaSession.ControllerInfo.isPackageNameVerified] would
 * close that gap but take both commands away from Android Auto on Android 9-12.
 */
@OptIn(UnstableApi::class)
internal fun MediaSession.isTrustedController(controller: MediaSession.ControllerInfo, ownPackage: String): Boolean {
    if (controller.isTrusted) return true
    val knownName = controller.isPackageNameVerified ||
        controller.controllerVersion == MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION
    return knownName && (
        controller.packageName == ownPackage ||
            isMediaNotificationController(controller) ||
            isAutoCompanionController(controller) ||
            isAutomotiveController(controller)
        )
}

/** What a controller may do on the session ([LibrarySessionCallback.onConnect]). */
internal class ControllerCommands(val session: SessionCommands, val player: Player.Commands)

/**
 * Trusted controllers get the library, the queue and the app's own commands; foreign apps only play, seek, change title and
 * volume, but neither browse, replace the queue nor set favorites (those would go to the server via the sync queue).
 */
@OptIn(UnstableApi::class)
internal fun controllerCommands(trusted: Boolean): ControllerCommands = if (trusted) {
    ControllerCommands(
        MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            .add(SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY))
            .add(SessionCommand(ACTION_CHAPTER_PREVIOUS, Bundle.EMPTY))
            .add(SessionCommand(ACTION_CHAPTER_NEXT, Bundle.EMPTY))
            .add(SessionCommand(ACTION_VOICE_CHECK, Bundle.EMPTY))
            .build(),
        MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS,
    )
} else {
    ControllerCommands(
        SessionCommands.EMPTY,
        MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
            .removeAll(Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS)
            .build(),
    )
}

/** Start of a replaced queue: index in the kept titles and position in it. */
internal data class QueueStart(val index: Int, val positionMs: Long)

/**
 * Where a replaced queue starts once unknown and unplayable titles are dropped ([LibrarySessionCallback.onSetMediaItems]).
 * [kept] are the positions of the remaining titles in the request. The requested title keeps its position; if it was dropped,
 * the next remaining one starts from the beginning (the first one if none follows). Without a requested start
 * ([C.INDEX_UNSET]) the player decides.
 */
internal fun queueStart(kept: List<Int>, requestedStart: Int, startPositionMs: Long): QueueStart {
    if (requestedStart == C.INDEX_UNSET) return QueueStart(C.INDEX_UNSET, startPositionMs)
    val index = kept.indexOfFirst { it >= requestedStart }
    val position = if (index >= 0 && kept[index] == requestedStart) startPositionMs else 0
    return QueueStart(index.coerceAtLeast(0), position)
}
