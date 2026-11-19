package de.kleinabersmart.earloft.core.playback

import android.content.Context
import androidx.annotation.StringRes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import de.kleinabersmart.earloft.core.model.SpokenMode

/** A fixed folder of the browse tree: a root tab or a subfolder of an area. */
internal data class BrowseFolder(
    val mediaId: String,
    @param:StringRes val title: Int,
    val mediaType: Int,
    /** Content as a grid (cover folders). */
    val gridChildren: Boolean = false,
) {
    fun toMediaItem(context: Context): MediaItem = folderMediaItem(mediaId, context.getString(title), mediaType, gridChildren)
}

/**
 * Android Auto's tabs (at most four) for the enabled [areas]: with several, "Recent" plus one tab per
 * area; with a single area its folders themselves, so no level is wasted (music only: as before).
 */
internal fun rootFolders(areas: List<SpokenMode>): List<BrowseFolder> {
    if (areas.size > 1) {
        return listOf(BrowseFolder(MediaIds.HOME, R.string.browse_recent, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)) + areas.map(::areaFolder)
    }
    return when (areas.singleOrNull() ?: SpokenMode.MUSIC) {
        SpokenMode.MUSIC -> listOf(
            BrowseFolder(MediaIds.RECENT, R.string.recently_played, MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, gridChildren = true),
            BrowseFolder(MediaIds.LIBRARY, R.string.library, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            BrowseFolder(MediaIds.PLAYLISTS, R.string.playlists, MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
            BrowseFolder(MediaIds.FAVORITES, R.string.favorites, MediaMetadata.MEDIA_TYPE_PLAYLIST),
        )
        SpokenMode.PODCASTS -> podcastFolders()
        SpokenMode.BOOKS -> listOf(
            BOOKS_CONTINUE,
            BrowseFolder(MediaIds.BOOKS_ALL, R.string.library, MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS, gridChildren = true),
            BOOK_AUTHORS,
            BOOK_SERIES,
        )
    }
}

/** An area's tab when several are enabled. */
internal fun areaFolder(area: SpokenMode): BrowseFolder = when (area) {
    SpokenMode.MUSIC -> BrowseFolder(MediaIds.MUSIC, R.string.area_music, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
    SpokenMode.PODCASTS -> BrowseFolder(MediaIds.PODCASTS, R.string.area_podcasts, MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS)
    SpokenMode.BOOKS -> BrowseFolder(MediaIds.BOOKS, R.string.area_books, MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
}

/** The folders in an area's tab ([areaFolder]). */
internal fun areaChildren(area: SpokenMode): List<BrowseFolder> = when (area) {
    SpokenMode.MUSIC -> listOf(
        BrowseFolder(MediaIds.RECENT, R.string.recently_played, MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, gridChildren = true),
        BrowseFolder(MediaIds.FAVORITES, R.string.favorites, MediaMetadata.MEDIA_TYPE_PLAYLIST),
        BrowseFolder(MediaIds.PLAYLISTS, R.string.playlists, MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
        BrowseFolder(MediaIds.ALBUMS, R.string.albums, MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS, gridChildren = true),
        BrowseFolder(MediaIds.ARTISTS, R.string.artists, MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
    )
    SpokenMode.PODCASTS -> podcastFolders()
    SpokenMode.BOOKS -> listOf(
        BOOKS_CONTINUE,
        BrowseFolder(MediaIds.BOOKS_RECENT, R.string.recently_added, MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS, gridChildren = true),
        BrowseFolder(MediaIds.BOOKS_ALL, R.string.all_books, MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS, gridChildren = true),
        BOOK_AUTHORS,
        BOOK_SERIES,
    )
}

/** The tab or folder behind [mediaId] ([MediaIds.MUSIC] etc.); `null` for other IDs. */
internal fun areaOfTab(mediaId: String): SpokenMode? = SpokenMode.entries.firstOrNull { areaFolder(it).mediaId == mediaId }

private fun podcastFolders() = listOf(
    BrowseFolder(MediaIds.EPISODES_CONTINUE, R.string.continue_listening, MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS),
    BrowseFolder(MediaIds.EPISODES_NEW, R.string.new_episodes, MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS),
    BrowseFolder(MediaIds.FEEDS, R.string.subscriptions, MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS, gridChildren = true),
    BrowseFolder(MediaIds.EPISODES_DOWNLOADED, R.string.downloaded, MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS),
)

private val BOOKS_CONTINUE =
    BrowseFolder(MediaIds.BOOKS_CONTINUE, R.string.continue_listening, MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS, gridChildren = true)
private val BOOK_AUTHORS = BrowseFolder(MediaIds.AUTHORS, R.string.authors, MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
private val BOOK_SERIES = BrowseFolder(MediaIds.SERIES, R.string.series, MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
