package de.kleinabersmart.earloft.core.data.share

import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.dao.LibraryDao
import de.kleinabersmart.earloft.core.database.dao.PodcastDao
import de.kleinabersmart.earloft.core.database.dao.SourceDao
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.PodcastFeedEntity
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.isArdSoundsBook
import de.kleinabersmart.earloft.core.network.podcast.FeedAuthRegistry
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ardSoundsPageUrl
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ardSoundsShowId
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** What can be shared (concept 13); deliberately not playlists. */
sealed interface ShareTarget {
    data class Song(val songId: Long) : ShareTarget
    data class Album(val albumId: Long) : ShareTarget
    data class Artist(val artistId: Long) : ShareTarget
    data class Feed(val feedId: String) : ShareTarget

    /** Book or episode; with [at] from this position. */
    data class Book(val bookId: Long, val at: SharePosition? = null) : ShareTarget
}

/** Position in a book or episode; [label] ("from 12:34") goes into the text as is. */
data class SharePosition(val positionMs: Long, val label: String)

/**
 * Builds title and link for sharing from the database alone; `null` if the entry no longer exists or it
 * can't be shared (podcasts from the device).
 */
@Singleton
class ShareRepository @Inject constructor(
    private val libraryDao: LibraryDao,
    private val podcastDao: PodcastDao,
    private val bookDao: BookDao,
    private val sourceDao: SourceDao,
    private val feedAuth: FeedAuthRegistry,
) {
    suspend fun content(target: ShareTarget): ShareContent? = when (target) {
        is ShareTarget.Song -> libraryDao.getSongEntity(target.songId)?.let { song ->
            val title = titleWithArtist(song.title, song.artistDisplay)
            content(title, ShareLinks.musicBrainz(MusicBrainzKind.RECORDING, song.mbid, song.title, song.artistDisplay))
        }
        is ShareTarget.Album -> libraryDao.getAlbumLookup(target.albumId)?.let { album ->
            val title = titleWithArtist(album.title, album.artist)
            content(title, ShareLinks.musicBrainz(MusicBrainzKind.RELEASE, album.mbid, album.title, album.artist))
        }
        is ShareTarget.Artist -> libraryDao.getArtist(target.artistId)?.let { artist ->
            content(artist.name, ShareLinks.musicBrainz(MusicBrainzKind.ARTIST, artist.mbid, artist.name))
        }
        is ShareTarget.Feed -> podcastDao.feed(target.feedId)?.takeIf(::isShareable)?.let { feed ->
            content(feed.title, *feedLinks(feed, ShareLinks.publicPage(feed.websiteUrl), ownHosts()))
        }
        is ShareTarget.Book -> bookDao.book(target.bookId)?.let { book ->
            when (book.kind) {
                BookKind.BOOK -> content(
                    titleWithArtist(book.title, ShareLinks.mainAuthor(book.author) ?: book.author),
                    target.at?.label,
                    // Books from ARD Sounds: their page there (as with the subscriptions from there).
                    book.webUrl?.takeIf { isArdSoundsBook(book.serverId) } ?: ShareLinks.openLibrary(book.isbn, book.title, book.author),
                )
                BookKind.EPISODE -> episode(book, target.at)
            }
        }
    }

    /**
     * With [at], the position is in the text; if the link points directly to the audio file, it also carries it as a time marker
     * (`#t=754`, Media Fragments – browsers jump there). Whether broadcasters' web pages understand a time marker is unknown.
     */
    private suspend fun episode(book: BookEntity, at: SharePosition?): ShareContent? {
        val feed = podcastDao.feed(book.serverId)?.takeIf(::isShareable) ?: return null
        val ownHosts = ownHosts()
        val audio = bookDao.tracks(book.bookId).firstOrNull()?.remoteFileId?.takeUnless { ShareLinks.isPrivateSource(it, ownHosts) }
        val website = ShareLinks.publicPage(book.webUrl)
        val page = website ?: audio?.let(ShareLinks::sourceUrl)?.let { url ->
            if (at == null) url else "${url.substringBefore('#')}#t=${at.positionMs / 1000}"
        }
        val links = feedLinks(feed, website = null, ownHosts)
        return content(book.title, feed.title, at?.label, page, *links)
    }

    /**
     * Page of the show (ARD Sounds) or website and feed URL (RSS). A private feed (access token, home network,
     * own server; [ShareLinks.isPrivateSource]) is left out – concept 13: never the user's own servers.
     */
    private fun feedLinks(feed: PodcastFeedEntity, website: String?, ownHosts: Set<String>): Array<String?> {
        val show = ardSoundsShowId(feed.url)
        if (show != null) return arrayOf(website ?: ardSoundsPageUrl(show))
        val rss = "RSS: ${feed.url}".takeUnless { ShareLinks.isPrivateSource(feed.url, ownHosts) }
        return arrayOf(website?.takeUnless { it == feed.url }, rss)
    }

    /**
     * Hosts of the configured music and Audiobookshelf servers and of the protected feeds: their feed and audio URLs
     * are useless to others without a password and reveal where the user pays or what they subscribe to privately.
     */
    private suspend fun ownHosts(): Set<String> =
        (sourceDao.getServers().mapNotNull { it.baseUrl } + bookDao.servers().map { it.baseUrl })
            .mapNotNullTo(HashSet()) { it.toHttpUrlOrNull()?.host?.lowercase() } + feedAuth.hosts()

    /** Podcasts from the device (`local:`) have no public address. */
    private fun isShareable(feed: PodcastFeedEntity) = ardSoundsShowId(feed.url) != null || ShareLinks.sourceUrl(feed.url) != null

    private fun titleWithArtist(title: String, artist: String?) =
        if (artist.isNullOrBlank()) title else "$title – $artist"

    private fun content(title: String, vararg lines: String?) =
        ShareContent(subject = title, text = (listOf(title) + lines.filterNotNull()).joinToString("\n"))
}
