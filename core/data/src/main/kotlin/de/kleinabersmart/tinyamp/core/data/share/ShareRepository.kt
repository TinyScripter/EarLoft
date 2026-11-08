package de.kleinabersmart.tinyamp.core.data.share

import de.kleinabersmart.tinyamp.core.database.dao.BookDao
import de.kleinabersmart.tinyamp.core.database.dao.LibraryDao
import de.kleinabersmart.tinyamp.core.database.dao.PodcastDao
import de.kleinabersmart.tinyamp.core.database.dao.SourceDao
import de.kleinabersmart.tinyamp.core.database.entity.BookEntity
import de.kleinabersmart.tinyamp.core.database.entity.PodcastFeedEntity
import de.kleinabersmart.tinyamp.core.model.BookKind
import de.kleinabersmart.tinyamp.core.network.podcast.ardsounds.ardSoundsPageUrl
import de.kleinabersmart.tinyamp.core.network.podcast.ardsounds.ardSoundsShowId
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Was sich teilen lässt (Konzept 13); Playlists bewusst nicht. */
sealed interface ShareTarget {
    data class Song(val songId: Long) : ShareTarget
    data class Album(val albumId: Long) : ShareTarget
    data class Artist(val artistId: Long) : ShareTarget
    data class Feed(val feedId: String) : ShareTarget

    /** Buch oder Folge. */
    data class Book(val bookId: Long) : ShareTarget
}

/**
 * Baut Titel und Link fürs Teilen allein aus der Datenbank; `null`, wenn es den Eintrag nicht mehr gibt oder er sich
 * nicht teilen lässt (Podcasts vom Gerät).
 */
@Singleton
class ShareRepository @Inject constructor(
    private val libraryDao: LibraryDao,
    private val podcastDao: PodcastDao,
    private val bookDao: BookDao,
    private val sourceDao: SourceDao,
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
                    ShareLinks.openLibrary(book.isbn, book.title, book.author),
                )
                BookKind.EPISODE -> episode(book)
            }
        }
    }

    private suspend fun episode(book: BookEntity): ShareContent? {
        val feed = podcastDao.feed(book.serverId)?.takeIf(::isShareable) ?: return null
        val ownHosts = ownHosts()
        val audio = bookDao.tracks(book.bookId).firstOrNull()?.remoteFileId?.takeUnless { ShareLinks.isPrivateSource(it, ownHosts) }
        val page = ShareLinks.publicPage(book.webUrl) ?: audio?.let(ShareLinks::sourceUrl)
        val links = feedLinks(feed, website = null, ownHosts)
        return content(book.title, feed.title, page, *links)
    }

    /**
     * Seite der Sendung (ARD Sounds) bzw. Webseite und Feed-Adresse (RSS). Ein privater Feed (Zugangstoken, Heimnetz,
     * eigener Server; [ShareLinks.isPrivateSource]) bleibt weg – Konzept 13: nie die eigenen Server.
     */
    private fun feedLinks(feed: PodcastFeedEntity, website: String?, ownHosts: Set<String>): Array<String?> {
        val show = ardSoundsShowId(feed.url)
        if (show != null) return arrayOf(website ?: ardSoundsPageUrl(show))
        val rss = "RSS: ${feed.url}".takeUnless { ShareLinks.isPrivateSource(feed.url, ownHosts) }
        return arrayOf(website?.takeUnless { it == feed.url }, rss)
    }

    /** Hosts der eingerichteten Musik- und Audiobookshelf-Server. */
    private suspend fun ownHosts(): Set<String> =
        (sourceDao.getServers().mapNotNull { it.baseUrl } + bookDao.servers().map { it.baseUrl })
            .mapNotNullTo(HashSet()) { it.toHttpUrlOrNull()?.host?.lowercase() }

    /** Podcasts vom Gerät (`local:`) haben keine öffentliche Adresse. */
    private fun isShareable(feed: PodcastFeedEntity) = ardSoundsShowId(feed.url) != null || ShareLinks.sourceUrl(feed.url) != null

    private fun titleWithArtist(title: String, artist: String?) =
        if (artist.isNullOrBlank()) title else "$title – $artist"

    private fun content(title: String, vararg lines: String?) =
        ShareContent(subject = title, text = (listOf(title) + lines.filterNotNull()).joinToString("\n"))
}
