package de.kleinabersmart.earloft.core.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.data.podcast.ContinueListening
import de.kleinabersmart.earloft.core.data.search.LibrarySearch
import de.kleinabersmart.earloft.core.data.spoken.BookRepository
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.Song
import de.kleinabersmart.earloft.core.model.SpokenMode
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Der Browse-Tree für Android Auto, die Uhr und das System (Konzept 7.1): Musik, Podcasts und Bücher, soweit sie unter
 * Allgemein → Bereiche eingeschaltet sind. Ausgeschaltete Bereiche fehlen in den Tabs, in „Zuletzt“, in der Suche und bei
 * Sprachbefehlen; IDs aus ihnen (Android Auto merkt sich Listen) gelten als verborgen ([isHidden]).
 */
internal class BrowseTree @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val music: MusicBrowse,
    private val podcasts: PodcastBrowse,
    private val books: BookBrowse,
    private val bookRepository: BookRepository,
    private val continueListening: ContinueListening,
    private val spokenSettings: SpokenSettings,
    private val librarySearch: LibrarySearch,
) {
    suspend fun areas(): List<SpokenMode> = spokenSettings.areas.first()

    /** Gehört [mediaId] zu einem ausgeschalteten Bereich (bei `spoken:` nach der Art des Buchs)? Unbekanntes ist nicht verborgen. */
    suspend fun isHidden(mediaId: String): Boolean {
        val areas = areas()
        MediaIds.spokenId(mediaId)?.let { return !isSpokenEnabled(it, areas) }
        val area = MediaIds.areaOf(mediaId) ?: return false
        return area !in areas
    }

    /** Ist der Bereich des Buchs bzw. der Folge [bookId] eingeschaltet? `false`, wenn es sie nicht gibt. */
    suspend fun isSpokenEnabled(bookId: Long, areas: List<SpokenMode>? = null): Boolean {
        val kind = bookRepository.bookNow(bookId)?.kind ?: return false
        return kind.area in (areas ?: areas())
    }

    /** Inhalt des Ordners [parentId]; `null`, wenn es ihn nicht gibt. */
    suspend fun children(parentId: String): List<MediaItem>? {
        val areas = areas()
        val tabArea = areaOfTab(parentId)
        return when {
            parentId == MediaIds.ROOT -> rootFolders(areas).map { it.toMediaItem(context) }
            parentId == MediaIds.HOME -> home(areas)
            tabArea != null -> areaChildren(tabArea).map { it.toMediaItem(context) }
            else -> music.children(parentId) ?: podcasts.children(parentId) ?: books.children(parentId)
        }
    }

    /** „Zuletzt“: je eingeschaltetem Bereich das Neueste unter dessen Namen als Überschrift. */
    private suspend fun home(areas: List<SpokenMode>): List<MediaItem> = areas.flatMap { area ->
        val title = context.getString(areaFolder(area).title)
        val items = when (area) {
            SpokenMode.MUSIC -> music.recentAlbums(HOME_GROUP_SIZE)
            SpokenMode.PODCASTS -> podcasts.continueEpisodes(HOME_GROUP_SIZE)
            SpokenMode.BOOKS -> books.continueBooks(HOME_GROUP_SIZE)
        }
        items.map { it.inGroup(title) }
    }

    /** Suchtreffer aller eingeschalteten Bereiche, nach Art gruppiert. Songs tragen die Suche als Ordner (Antippen reiht alle ein). */
    suspend fun search(query: String): List<MediaItem> {
        val hits = librarySearch.search(query)
        return buildList {
            addGroup(R.string.albums, hits.music.albums.map { it.toBrowsableMediaItem(context) })
            addGroup(R.string.artists, hits.music.artists.map { it.toBrowsableMediaItem() })
            addGroup(R.string.songs, hits.music.songs.map { it.toBrowsableSongItem(context, MediaIds.search(query)) })
            addGroup(R.string.area_podcasts, hits.feeds.map { it.toBrowsableMediaItem(context) })
            addGroup(R.string.episodes, podcasts.episodeItems(hits.episodes))
            addGroup(R.string.area_books, books.bookItems(hits.books))
            addGroup(R.string.authors, hits.authors.map { bookFolder(MediaIds.author(it.name), it.name, it.bookCount, it.firstBookId) })
            addGroup(R.string.series, hits.series.map { bookFolder(MediaIds.series(it.name), it.name, it.bookCount, it.firstBookId) })
        }
    }

    /** Die Songs eines Musik-Ordners; `null`, wenn es keiner ist oder Musik ausgeschaltet ist. */
    suspend fun songsOf(folderId: String): List<Song>? =
        if (SpokenMode.MUSIC in areas()) music.songsOf(folderId) else null

    /** Der Eintrag eines Buchs bzw. einer Folge (`spoken:<id>`). */
    suspend fun spokenItem(bookId: Long): MediaItem? {
        val item = bookRepository.book(bookId).first() ?: return null
        return if (item.book.kind == BookKind.EPISODE) {
            podcasts.episodeItems(listOf(item)).firstOrNull()
        } else {
            books.bookItems(listOf(item)).firstOrNull()
        }
    }

    /** Was auf „Spiele [query]“ läuft: immer [VoiceTarget.Songs] (evtl. leer) oder [VoiceTarget.Spoken]. */
    suspend fun voice(query: String): VoiceTarget = when (val target = chooseVoiceTarget(query, librarySearch.search(query))) {
        is VoiceTarget.Spoken, is VoiceTarget.Songs -> target
        is VoiceTarget.Feed -> podcasts.startEpisodeOf(target.feedId)?.let { VoiceTarget.Spoken(it) } ?: VoiceTarget.Songs(emptyList())
        is VoiceTarget.Artist -> VoiceTarget.Songs(music.artistSongs(target.artistId))
        is VoiceTarget.Album -> VoiceTarget.Songs(music.albumSongs(target.albumId))
        VoiceTarget.None -> VoiceTarget.Songs(emptyList())
    }

    /**
     * „Spiele“ ohne Begriff, wenn nichts fortzusetzen ist: gemischte Favoriten; ist Musik aus, die erste Folge bzw. das erste
     * Buch aus „Weiterhören“ der eingeschalteten Bereiche.
     */
    suspend fun voiceWithoutQuery(): VoiceTarget {
        val areas = areas()
        if (SpokenMode.MUSIC in areas) return VoiceTarget.Songs(music.shuffledFavorites(VOICE_QUEUE_SIZE))
        for (area in areas) {
            val id = when (area) {
                SpokenMode.PODCASTS -> continueListening.episodes.first().firstOrNull()?.book?.id
                SpokenMode.BOOKS -> books.firstStartedId()
                SpokenMode.MUSIC -> null
            }
            if (id != null) return VoiceTarget.Spoken(id)
        }
        return VoiceTarget.Songs(emptyList())
    }

    private fun MutableList<MediaItem>.addGroup(title: Int, items: List<MediaItem>) {
        if (items.isEmpty()) return
        val name = context.getString(title)
        items.mapTo(this) { it.inGroup(name) }
    }

    private fun bookFolder(mediaId: String, name: String, count: Int, coverBookId: Long): MediaItem =
        folderMediaItem(
            mediaId,
            name,
            MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS,
            gridChildren = true,
            subtitle = context.resources.getQuantityString(R.plurals.book_count, count, count),
            artworkUri = CoverProvider.bookCoverUri(context, coverBookId),
        )

    private companion object {
        const val HOME_GROUP_SIZE = 6
        const val VOICE_QUEUE_SIZE = 100
    }
}

/** Der Bereich eines Buchs bzw. einer Folge. */
internal val BookKind.area: SpokenMode get() = if (this == BookKind.EPISODE) SpokenMode.PODCASTS else SpokenMode.BOOKS

/** Der Bereich eines Eintrags der Warteschlange; `null`, wenn er keinem zuzuordnen ist. */
internal val MediaItem.area: SpokenMode?
    get() = when {
        isSpoken -> if (isEpisode) SpokenMode.PODCASTS else SpokenMode.BOOKS
        songId != null -> SpokenMode.MUSIC
        else -> null
    }
