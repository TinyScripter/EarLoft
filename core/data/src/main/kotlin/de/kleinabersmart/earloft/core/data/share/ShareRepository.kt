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

/** Was sich teilen lässt (Konzept 13); Playlists bewusst nicht. */
sealed interface ShareTarget {
    data class Song(val songId: Long) : ShareTarget
    data class Album(val albumId: Long) : ShareTarget
    data class Artist(val artistId: Long) : ShareTarget
    data class Feed(val feedId: String) : ShareTarget

    /** Buch oder Folge; mit [at] ab dieser Stelle. */
    data class Book(val bookId: Long, val at: SharePosition? = null) : ShareTarget
}

/** Stelle in einem Buch oder einer Folge; [label] („ab 12:34“) kommt so in den Text. */
data class SharePosition(val positionMs: Long, val label: String)

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
                    // Bücher aus ARD Sounds: ihre Seite dort (wie bei den Abos von dort).
                    book.webUrl?.takeIf { isArdSoundsBook(book.serverId) } ?: ShareLinks.openLibrary(book.isbn, book.title, book.author),
                )
                BookKind.EPISODE -> episode(book, target.at)
            }
        }
    }

    /**
     * Mit [at] steht die Stelle im Text; zeigt der Link direkt auf die Audiodatei, trägt er sie auch als Zeitmarke
     * (`#t=754`, Media Fragments – Browser springen dorthin). Ob Webseiten der Sender eine Zeitmarke kennen, ist offen.
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
     * Seite der Sendung (ARD Sounds) bzw. Webseite und Feed-Adresse (RSS). Ein privater Feed (Zugangstoken, Heimnetz,
     * eigener Server; [ShareLinks.isPrivateSource]) bleibt weg – Konzept 13: nie die eigenen Server.
     */
    private fun feedLinks(feed: PodcastFeedEntity, website: String?, ownHosts: Set<String>): Array<String?> {
        val show = ardSoundsShowId(feed.url)
        if (show != null) return arrayOf(website ?: ardSoundsPageUrl(show))
        val rss = "RSS: ${feed.url}".takeUnless { ShareLinks.isPrivateSource(feed.url, ownHosts) }
        return arrayOf(website?.takeUnless { it == feed.url }, rss)
    }

    /**
     * Hosts der eingerichteten Musik- und Audiobookshelf-Server und der geschützten Feeds: Deren Feed- und Audio-Adressen
     * nützen anderen ohne Passwort nichts und verraten, wo der Nutzer zahlt bzw. was er privat abonniert.
     */
    private suspend fun ownHosts(): Set<String> =
        (sourceDao.getServers().mapNotNull { it.baseUrl } + bookDao.servers().map { it.baseUrl })
            .mapNotNullTo(HashSet()) { it.toHttpUrlOrNull()?.host?.lowercase() } + feedAuth.hosts()

    /** Podcasts vom Gerät (`local:`) haben keine öffentliche Adresse. */
    private fun isShareable(feed: PodcastFeedEntity) = ardSoundsShowId(feed.url) != null || ShareLinks.sourceUrl(feed.url) != null

    private fun titleWithArtist(title: String, artist: String?) =
        if (artist.isNullOrBlank()) title else "$title – $artist"

    private fun content(title: String, vararg lines: String?) =
        ShareContent(subject = title, text = (listOf(title) + lines.filterNotNull()).joinToString("\n"))
}
