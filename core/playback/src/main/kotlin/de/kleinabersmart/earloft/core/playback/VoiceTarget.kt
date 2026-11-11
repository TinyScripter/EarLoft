package de.kleinabersmart.earloft.core.playback

import de.kleinabersmart.earloft.core.data.search.AreaSearchResults
import de.kleinabersmart.earloft.core.model.Song

/** Was auf „Spiele …“ hin läuft (Android Auto, Assistant, `MEDIA_PLAY_FROM_SEARCH`). */
internal sealed interface VoiceTarget {
    /** Ein Buch oder eine Folge. */
    data class Spoken(val bookId: Long) : VoiceTarget

    /** Ein Podcast: seine nächste Folge ([PodcastBrowse.startEpisodeOf]). */
    data class Feed(val feedId: String) : VoiceTarget
    data class Songs(val songs: List<Song>) : VoiceTarget
    data class Artist(val artistId: Long) : VoiceTarget
    data class Album(val albumId: Long) : VoiceTarget
    data object None : VoiceTarget
}

/**
 * Wählt aus den Treffern der eingeschalteten Bereiche: zuerst genaue Titel (Buch, Podcast, Song, Folge), dann wie bisher bei
 * der Musik der erste Künstler bzw. das erste Album, dann der erste Podcast, das erste Buch (die Suche findet Bücher auch über
 * den Autor), die erste Folge, zuletzt alle passenden Songs.
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
