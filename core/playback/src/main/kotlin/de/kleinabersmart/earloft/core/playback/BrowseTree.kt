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
 * The browse tree for Android Auto, the watch and the system (concept 7.1): music, podcasts and books, as far as they are
 * enabled under General → Areas. Disabled areas are missing from the tabs, from "Recent", from search and from
 * voice commands; IDs from them (Android Auto remembers lists) count as hidden ([isHidden]).
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

    /** Does [mediaId] belong to a disabled area (for `spoken:` by the kind of book)? Unknown IDs are not hidden. */
    suspend fun isHidden(mediaId: String): Boolean {
        val areas = areas()
        MediaIds.spokenId(mediaId)?.let { return !isSpokenEnabled(it, areas) }
        val area = MediaIds.areaOf(mediaId) ?: return false
        return area !in areas
    }

    /** Is the area of the book or episode [bookId] enabled? `false` if it doesn't exist. */
    suspend fun isSpokenEnabled(bookId: Long, areas: List<SpokenMode>? = null): Boolean {
        val kind = bookRepository.bookNow(bookId)?.kind ?: return false
        return kind.area in (areas ?: areas())
    }

    /** Content of the folder [parentId]; `null` if it doesn't exist. */
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

    /** "Recent": for each enabled area its newest items, under the area's name as heading. */
    private suspend fun home(areas: List<SpokenMode>): List<MediaItem> = areas.flatMap { area ->
        val title = context.getString(areaFolder(area).title)
        val items = when (area) {
            SpokenMode.MUSIC -> music.recentAlbums(HOME_GROUP_SIZE)
            SpokenMode.PODCASTS -> podcasts.continueEpisodes(HOME_GROUP_SIZE)
            SpokenMode.BOOKS -> books.continueBooks(HOME_GROUP_SIZE)
        }
        items.map { it.inGroup(title) }
    }

    /** Search results of all enabled areas, grouped by kind. Songs carry the search as folder (tapping enqueues all). */
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

    /** The songs of a music folder; `null` if it isn't one or music is disabled. */
    suspend fun songsOf(folderId: String): List<Song>? =
        if (SpokenMode.MUSIC in areas()) music.songsOf(folderId) else null

    /** The entry of a book or episode (`spoken:<id>`). */
    suspend fun spokenItem(bookId: Long): MediaItem? {
        val item = bookRepository.book(bookId).first() ?: return null
        return if (item.book.kind == BookKind.EPISODE) {
            podcasts.episodeItems(listOf(item)).firstOrNull()
        } else {
            books.bookItems(listOf(item)).firstOrNull()
        }
    }

    /** What plays on "Play [query]": always [VoiceTarget.Songs] (possibly empty) or [VoiceTarget.Spoken]. */
    suspend fun voice(query: String): VoiceTarget = when (val target = chooseVoiceTarget(query, librarySearch.search(query))) {
        is VoiceTarget.Spoken, is VoiceTarget.Songs -> target
        is VoiceTarget.Feed -> podcasts.startEpisodeOf(target.feedId)?.let { VoiceTarget.Spoken(it) } ?: VoiceTarget.Songs(emptyList())
        is VoiceTarget.Artist -> VoiceTarget.Songs(music.artistSongs(target.artistId))
        is VoiceTarget.Album -> VoiceTarget.Songs(music.albumSongs(target.albumId))
        VoiceTarget.None -> VoiceTarget.Songs(emptyList())
    }

    /**
     * "Play" without a term when there is nothing to resume: shuffled favorites; if music is off, the first episode or the first
     * book from "Continue listening" of the enabled areas.
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

/** The area of a book or episode. */
internal val BookKind.area: SpokenMode get() = if (this == BookKind.EPISODE) SpokenMode.PODCASTS else SpokenMode.BOOKS

/** The area of a queue entry; `null` if it can't be assigned to one. */
internal val MediaItem.area: SpokenMode?
    get() = when {
        isSpoken -> if (isEpisode) SpokenMode.PODCASTS else SpokenMode.BOOKS
        songId != null -> SpokenMode.MUSIC
        else -> null
    }
