package de.kleinabersmart.earloft.core.playback

import de.kleinabersmart.earloft.core.data.search.AreaSearchResults
import de.kleinabersmart.earloft.core.model.Song

/** What plays on "Play …" (Android Auto, Assistant, `MEDIA_PLAY_FROM_SEARCH`). */
internal sealed interface VoiceTarget {
    /** A book or an episode. */
    data class Spoken(val bookId: Long) : VoiceTarget

    /** A podcast: its next episode ([PodcastBrowse.startEpisodeOf]). */
    data class Feed(val feedId: String) : VoiceTarget
    data class Songs(val songs: List<Song>) : VoiceTarget
    data class Artist(val artistId: Long) : VoiceTarget
    data class Album(val albumId: Long) : VoiceTarget
    data object None : VoiceTarget
}

/**
 * Picks from the results of the enabled areas: exact titles first (book, podcast, song, episode), then, as before for
 * music, the first artist or the first album, then the first podcast, the first book (search also finds books via
 * the author), the first episode, and finally all matching songs.
 */
internal fun chooseVoiceTarget(query: String, hits: AreaSearchResults): VoiceTarget {
    val wanted = query.trim()
    fun String.isWanted() = trim().equals(wanted, ignoreCase = true)

    hits.books.firstOrNull { it.book.title.isWanted() }?.let { return VoiceTarget.Spoken(it.book.id) }
    hits.feeds.firstOrNull { it.title.isWanted() }?.let { return VoiceTarget.Feed(it.id) }
    hits.music.songs.filter { it.title.isWanted() }.takeIf { it.isNotEmpty() }?.let { return VoiceTarget.Songs(it) }
    hits.episodes.firstOrNull { it.book.title.isWanted() }?.let { return VoiceTarget.Spoken(it.book.id) }
    hits.music.artists.firstOrNull()?.let { return VoiceTarget.Artist(it.id) }
    hits.music.albums.firstOrNull()?.let { return VoiceTarget.Album(it.id) }
    hits.feeds.firstOrNull()?.let { return VoiceTarget.Feed(it.id) }
    hits.books.firstOrNull()?.let { return VoiceTarget.Spoken(it.book.id) }
    hits.episodes.firstOrNull()?.let { return VoiceTarget.Spoken(it.book.id) }
    return if (hits.music.songs.isNotEmpty()) VoiceTarget.Songs(hits.music.songs) else VoiceTarget.None
}
