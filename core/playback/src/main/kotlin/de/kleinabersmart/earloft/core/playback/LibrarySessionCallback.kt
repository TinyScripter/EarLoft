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

/** Benutzerdefinierte Aktion „Favorit umschalten“ (Herz in Android Auto, Benachrichtigung, Uhr). */
internal const val ACTION_TOGGLE_FAVORITE = "de.kleinabersmart.earloft.TOGGLE_FAVORITE"

/** Kapitel zurück bzw. vor als Knopf in Benachrichtigung und Auto (`books_improvements.md` M4). */
internal const val ACTION_CHAPTER_PREVIOUS = "de.kleinabersmart.earloft.CHAPTER_PREVIOUS"
internal const val ACTION_CHAPTER_NEXT = "de.kleinabersmart.earloft.CHAPTER_NEXT"

/**
 * Browse-Tree und Auflösung von Media-Items für alle Controller (App, System, Uhr, Android Auto).
 *
 * Den Inhalt des Trees liefert [BrowseTree] (Tabs je nach eingeschalteten Bereichen, höchstens vier). Songs in Listen
 * tragen ihren Ordner in der Media-ID ([MediaIds.inContext]); wird einer davon allein gesetzt (Antippen in Auto), lädt
 * [onSetMediaItems] die ganze Liste als Warteschlange. Ein Buch bzw. eine Folge (`spoken:<id>`) bereitet die Session
 * selbst vor wie die App ([startSpoken]).
 *
 * Controller schicken Items ohne abspielbare URI über Prozessgrenzen; [onAddMediaItems] und
 * [onSetMediaItems] bauen sie deshalb anhand der Media-ID neu aus der Datenbank. Songs, die der
 * aktuelle Player nicht spielen kann ([SessionPlayerFactory.playableSongIds], z. B. beim Casten),
 * fallen dabei weg.
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
    /** Kopfhörer- und Medientasten bei Büchern und Folgen; `null`: alles wie Media3. */
    private val mediaButtons: SpokenMediaButtons? = null,
    /** Kapitelknöpfe ([ACTION_CHAPTER_NEXT] = `true`); auf dem Main-Thread. */
    private val onChapterButton: (forward: Boolean) -> Unit = {},
    /** Wartet, bis der Service die letzte Warteschlange wiederhergestellt hat ([PlaybackService.restoreQueue]). */
    private val awaitRestore: suspend () -> Unit = {},
) : MediaLibrarySession.Callback {

    /**
     * Muss sofort fertig sein: Für alte `MediaBrowser` (Android Auto, System) beantwortet Media3 `onGetRoot` auf dem
     * Main-Thread und wartet dort ohne Zeitlimit auf dieses Future. Ein `scope.future` auf dem Main-Dispatcher käme nie dran
     * (App hängt, ANR).
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
        // Android Auto merkt sich Listen: Ordner eines inzwischen ausgeschalteten Bereichs gibt es nicht mehr.
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
     * Zuletzt gesuchter Begriff samt eingeschalteten Bereichen und seine Treffer: Auf [onSearch] folgt gleich
     * [onGetSearchResult], oft seitenweise.
     */
    private var lastSearch: Pair<Pair<String, List<SpokenMode>>, List<MediaItem>>? = null

    /** Läuft auf dem Main-Thread (scope), [lastSearch] braucht also keine Sperre. */
    private suspend fun searchResults(query: String): List<MediaItem> {
        val key = query to browseTree.areas()
        lastSearch?.takeIf { it.first == key }?.let { return it.second }
        val items = browseTree.search(query)
        lastSearch = key to items
        return items
    }

    /**
     * Nimmt jedes Abo eines Ordners an, der nicht zu einem ausgeschalteten Bereich gehört. Der Standard prüft über
     * [onGetItem], das nur Songs und Bücher kennt, und lehnte damit alle Ordner ab; Android Auto (Compat-Browser) erfuhr dann
     * nie von [MediaLibrarySession.notifyChildrenChanged] (Bereiche umgeschaltet).
     */
    override fun onSubscribe(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = scope.future {
        if (browseTree.isHidden(parentId)) return@future LibraryResult.ofError(areaDisabled())
        // Wie der Standard: Media3-Browser holen den Inhalt daraufhin, alte bekommen ihn ohnehin direkt.
        if (browser.controllerVersion != MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION) {
            session.notifyChildrenChanged(browser, parentId, Int.MAX_VALUE, params)
        }
        LibraryResult.ofVoid()
    }

    /** Bei Büchern und Folgen springen Doppelklick und „Weiter“ ins nächste Kapitel ([SpokenMediaButtons]). */
    override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent): Boolean {
        val event = keyEventOf(intent) ?: return false
        val external = controllerInfo.controllerVersion == MediaSession.ControllerInfo.LEGACY_CONTROLLER_VERSION
        return mediaButtons?.onMediaButton(event, external) ?: false
    }

    /** Wie Media3 (`DefaultActionFactory.getKeyEvent`): kaputte Extras sind keine Taste. */
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
     * Der Service ist für System, Uhr und Android Auto exportiert, damit aber für jede App erreichbar. Vertrauenswürdige
     * Controller ([isTrustedController]) bekommen alles; fremde Apps dürfen die Wiedergabe steuern, aber weder die Bibliothek
     * durchsuchen noch die Warteschlange ersetzen noch Favoriten setzen (die gingen per Sync-Queue an den Server).
     */
    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
        val trusted = session.isTrustedController(controller)
        Log.d(TAG, "Controller ${controller.packageName}: ${if (trusted) "vertraut" else "nur Abspielsteuerung"}")
        if (!trusted) {
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(SessionCommands.EMPTY)
                .setAvailablePlayerCommands(UNTRUSTED_PLAYER_COMMANDS)
                .build()
        }
        grantCoverAccess(controller.packageName)
        val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            .add(SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY))
            .add(SessionCommand(ACTION_CHAPTER_PREVIOUS, Bundle.EMPTY))
            .add(SessionCommand(ACTION_CHAPTER_NEXT, Bundle.EMPTY))
            .build()
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller).setAvailableSessionCommands(commands).build()
    }

    /**
     * Die eigene App, das System (Benachrichtigung, Sperrbildschirm, Fortsetzen nach dem Neustart; auch Apps mit
     * Medien-Steuerrecht oder als Benachrichtigungs-Listener wie die Uhr-Begleit-App) und Android Auto/Automotive.
     */
    private fun MediaSession.isTrustedController(controller: MediaSession.ControllerInfo): Boolean =
        controller.packageName == context.packageName ||
            controller.isTrusted ||
            isMediaNotificationController(controller) ||
            isAutoCompanionController(controller) ||
            isAutomotiveController(controller)

    /**
     * Die System-Oberfläche liest die Metadaten der Plattform-Session direkt und lädt das Cover selbst; verbinden ([onConnect])
     * tut sie sich erst mit dem ersten Befehl (Pause, Weiter). Deshalb bekommt sie die Freigabe gleich beim Start des Service.
     * Android verwirft Freigaben mit jedem Update der App und jedem Neustart des Geräts.
     */
    fun grantSystemUiCoverAccess() {
        systemUiPackage()?.let(::grantCoverAccess)
    }

    /**
     * Paket der System-Oberfläche aus der Geräte-Konfiguration (`config_systemUi`, nur über den Namen erreichbar), sonst der
     * AOSP-Name. Nicht über den PackageManager prüfen: Die Paket-Sichtbarkeit ab API 30 verbirgt es; ein falscher Name lässt
     * nur die Freigabe scheitern ([grantCoverAccess]).
     */
    @SuppressLint("DiscouragedApi")
    private fun systemUiPackage(): String? {
        val system = Resources.getSystem()
        val id = system.getIdentifier("config_systemUi", "string", "android")
        return (if (id != 0) system.getString(id) else null)?.takeIf { it.isNotBlank() } ?: DEFAULT_SYSTEM_UI_PACKAGE
    }

    /**
     * Der [CoverProvider] ist nicht exportiert: Vertrauenswürdige Controller bekommen Leserecht auf alle Cover (eine
     * Präfix-Freigabe), damit Android Auto bzw. die System-Oberfläche die `content://`-Cover der Listen laden können.
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
            Log.w(TAG, "Cover für $packageName nicht freigegeben", e)
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
            // Hinter dem, was der Player hält, liegt noch mehr: neue Titel kommen an dessen Ende.
            factory.appendPending(items)
            return@future emptyList()
        }
        // Was nicht mehr in den Player passt (Cast: höchstens 50 Titel insgesamt), reicht die Fabrik später nach.
        val room = factory.roomIn(player)
        if (items.size <= room) return@future items
        factory.appendPending(items.drop(room))
        items.take(room)
    }

    /**
     * Wie [onAddMediaItems]; fällt der Starttitel weg, beginnt die Wiedergabe beim nächsten verbliebenen.
     * Ist die Liste länger als der Player erlaubt, kommt ein Ausschnitt mit dem Starttitel ([queueWindow]);
     * den Rest reicht die Fabrik später nach ([SessionPlayerFactory.onQueueReplaced]).
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
                is VoiceStart.Songs -> target.songs.map { it.toMediaItem() } to 0
            }
            else -> expandContext(mediaItems, startIndex)
        }
        val resolved = resolve(player, requested)
        if (resolved.isEmpty() && requested.isNotEmpty()) {
            // Nichts abspielbar: Warteschlange behalten statt sie zu leeren.
            throw UnsupportedOperationException("Keiner der Titel ist abspielbar")
        }
        val items = resolved.map { it.second }
        val newIndex = resolved.indexOfFirst { it.first >= requestedStart }.takeIf { requestedStart != C.INDEX_UNSET }
        val position = when {
            newIndex == null -> startPositionMs
            newIndex >= 0 && resolved[newIndex].first == requestedStart -> startPositionMs
            else -> 0
        }
        val maxSize = maxQueueSize(player)
        if (items.size <= maxSize) {
            playerFactory?.onQueueReplaced(emptyList(), emptyList())
            return@future MediaItemsWithStartPosition(items, newIndex?.coerceAtLeast(0) ?: C.INDEX_UNSET, position)
        }
        val window = queueWindow(items, newIndex?.coerceAtLeast(0) ?: 0, maxSize, player.shuffleModeEnabled)
        playerFactory?.onQueueReplaced(window.before, window.after)
        MediaItemsWithStartPosition(window.items, window.startIndex, position)
    }

    /**
     * Ein einzelner Song aus einer Liste (Android Auto schickt nur die Media-ID) wird zur ganzen Liste mit diesem
     * Song als Start. Alles andere bleibt unverändert.
     */
    private suspend fun expandContext(mediaItems: List<MediaItem>, startIndex: Int): Pair<List<MediaItem>, Int> {
        val item = mediaItems.singleOrNull() ?: return mediaItems to startIndex
        val folderId = MediaIds.contextOf(item.mediaId) ?: return mediaItems to startIndex
        val songs = browseTree.songsOf(folderId) ?: return mediaItems to startIndex
        val index = songs.indexOfFirst { it.id == item.songId }
        if (index < 0) return mediaItems to startIndex
        return songs.map { it.toMediaItem() } to index
    }

    /** Wie die Session auf „Spiele …“ startet. */
    private sealed interface VoiceStart {
        /** Fertige Warteschlange (Fortsetzen). */
        class Ready(val items: MediaItemsWithStartPosition) : VoiceStart
        class Spoken(val bookId: Long) : VoiceStart
        class Songs(val songs: List<Song>) : VoiceStart
    }

    /**
     * Was auf „Spiele …“ hin läuft, nur aus eingeschalteten Bereichen. Mit Begriff: [BrowseTree.voice]. Ohne Begriff wird
     * fortgesetzt: was im Player steht, sonst das zuletzt gehörte Buch bzw. die Folge, sonst die gespeicherte
     * Musik-Warteschlange; gibt es nichts davon, [BrowseTree.voiceWithoutQuery].
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
     * Ein Buch bzw. eine Folge aus dem Browse-Tree oder per Sprachbefehl: wie [PlaybackConnection.playBook] in der App
     * (gespeicherte Stelle mit Zurückspulen, Tempo, ohne Shuffle/Wiederholen, Folge vorn in „Weiterhören“).
     */
    private suspend fun startSpoken(player: Player, bookId: Long): MediaItemsWithStartPosition {
        if (!browseTree.isSpokenEnabled(bookId)) throw UnsupportedOperationException("Bereich von Buch $bookId ausgeschaltet")
        continueListening.started(bookId)
        val prepared = withContext(Dispatchers.IO) { bookPlaybackSource.prepare(bookId) }
            ?: throw UnsupportedOperationException("Buch $bookId nicht abspielbar")
        val start = prepared.timeline.locate(prepared.startPositionMs)
        playerFactory?.onQueueReplaced(emptyList(), emptyList())
        player.shuffleModeEnabled = false
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.setPlaybackSpeed(prepared.speed)
        return MediaItemsWithStartPosition(prepared.toMediaItems(), start.trackIndex, start.offsetMs)
    }

    private fun maxQueueSize(player: Player): Int = playerFactory?.maxQueueSize(player) ?: Int.MAX_VALUE

    /** Neu aufgebaute Items samt ihrer Position in [mediaItems]; unbekannte und nicht abspielbare Songs fehlen. */
    private suspend fun resolve(player: Player, mediaItems: List<MediaItem>): List<Pair<Int, MediaItem>> {
        val songIds = mediaItems.mapNotNull { it.songId }.distinct()
        val songs = libraryRepository.songs(songIds).associateBy { it.id }
        val playable = playerFactory?.playableSongIds(player, songs.keys) ?: songs.keys
        return mediaItems.mapIndexedNotNull { index, item ->
            // Hörbücher sind schon fertig aufgebaut (BookMediaItems) und gehen unverändert durch.
            if (item.isSpoken) return@mapIndexedNotNull index to item
            item.songId?.takeIf { it in playable }?.let(songs::get)?.let { index to it.toMediaItem() }
        }
    }

    /**
     * [isForPlayback] `false`: Die System-Oberfläche will nur Metadaten für die Fortsetzen-Benachrichtigung (z. B. nach
     * dem Neustart des Geräts); dann genügt der aktuelle Titel, und am Player ändert sich nichts.
     */
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
        // Das Cover der Fortsetzen-Karte lädt die System-Oberfläche selbst; die Freigabe dafür gab es schon beim Start des
        // Service ([grantSystemUiCoverAccess]).
        if (isForPlayback) {
            // Kommt „Abspielen“ (Kopfhörer, Widget, Fortsetzen-Karte) gleich nach dem Start des Service, läuft dessen
            // Wiederherstellung noch. Gewann sie, steht das Buch schon im Player und hat sich die Pause gemerkt; ein
            // zweites Laden über prepare() spulte dann doppelt zurück (prepare wendet die Regel an, das Abspielen
            // danach noch einmal). Dann bleibt es beim geladenen Stand.
            awaitRestore()
            val player = mediaSession.player
            if (player.mediaItemCount > 0) {
                val items = (0 until player.mediaItemCount).map(player::getMediaItemAt)
                return@future MediaItemsWithStartPosition(items, player.currentMediaItemIndex, player.currentPosition)
            }
        }
        // Lief zuletzt ein Buch oder eine Folge, wird diese fortgesetzt statt der Musik-Warteschlange; nicht, wenn ihr
        // Bereich inzwischen ausgeschaltet ist.
        val areas = browseTree.areas()
        queuePersistence.lastBookId()?.takeIf { browseTree.isSpokenEnabled(it, areas) }?.let { bookId ->
            // Nur Metadaten (System-Oberfläche): ohne Zurückspulen und ohne Schreiben, wie beim Start des Service.
            val prepared = withContext(Dispatchers.IO) {
                if (isForPlayback) bookPlaybackSource.prepare(bookId) else bookPlaybackSource.restore(bookId)
            }
            if (prepared != null) {
                val start = prepared.timeline.locate(prepared.startPositionMs)
                val items = prepared.toMediaItems()
                if (!isForPlayback) {
                    // Wie bei Musik: Die System-Oberfläche kennt earloft://cover nicht, also die CoverProvider-URI.
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
        if (SpokenMode.MUSIC !in areas) throw UnsupportedOperationException("Musik ausgeschaltet")
        val restored = queuePersistence.restore() ?: throw UnsupportedOperationException("Keine gespeicherte Warteschlange")
        if (!isForPlayback) {
            val current = restored.items.getOrNull(restored.startIndex)
                ?: throw UnsupportedOperationException("Keine gespeicherte Warteschlange")
            // Die System-Oberfläche lädt das Cover selbst und kennt earloft://cover nicht (siehe CoverProvider).
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

        /** Abspielen, Spulen, Titel wechseln, Lautstärke – aber keine Titel setzen oder die Warteschlange ändern. */
        val UNTRUSTED_PLAYER_COMMANDS: Player.Commands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
            .removeAll(Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS)
            .build()
    }
}
