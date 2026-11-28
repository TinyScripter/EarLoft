package de.kleinabersmart.earloft.notification

import de.kleinabersmart.earloft.core.data.refresh.NewAlbum
import de.kleinabersmart.earloft.core.data.refresh.NewBook
import de.kleinabersmart.earloft.core.data.refresh.NewContent
import de.kleinabersmart.earloft.core.data.refresh.NewEpisodes
import de.kleinabersmart.earloft.core.data.refresh.NewShow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * [NewContent] in the extras of its notification, so the next one of the section can take over what is still unread there
 * (review5 P19). Only within one installation: an unknown or broken value counts as nothing unread.
 */
internal object NewContentJson {

    fun encode(content: NewContent): String = when (content) {
        is NewContent.Music -> JSONObject()
            .put(TYPE, MUSIC)
            .put(ALBUMS, content.albums.toJsonArray { album ->
                JSONObject().put("id", album.albumId).put("title", album.title).put("artist", album.artist).put("songs", album.songs)
            })
        is NewContent.Podcasts -> JSONObject()
            .put(TYPE, PODCASTS)
            .put(EPISODES, content.episodes.toJsonArray { feed ->
                JSONObject()
                    .put("feedId", feed.feedId)
                    .put("title", feed.feedTitle)
                    .put("ids", JSONArray(feed.episodeIds))
                    .put("notify", feed.notify)
                    .put("autoDownload", feed.autoDownload)
            })
            .put(MOVED, JSONArray(content.moved))
        is NewContent.Books -> JSONObject()
            .put(TYPE, BOOKS)
            .put(BOOK_LIST, content.books.toJsonArray { book ->
                JSONObject().put("id", book.bookId).put("title", book.title).put("author", book.author).apply {
                    book.ardShow?.let { put("showId", it.id).put("showTitle", it.title) }
                }
            })
    }.toString()

    fun decode(json: String?): NewContent? {
        if (json == null) return null
        return try {
            val root = JSONObject(json)
            when (root.getString(TYPE)) {
                MUSIC -> NewContent.Music(
                    root.getJSONArray(ALBUMS).objects().map {
                        NewAlbum(it.getLong("id"), it.getString("title"), it.getString("artist"), it.getInt("songs"))
                    },
                )
                PODCASTS -> NewContent.Podcasts(
                    episodes = root.getJSONArray(EPISODES).objects().map {
                        val ids = it.getJSONArray("ids")
                        NewEpisodes(
                            feedId = it.getString("feedId"),
                            feedTitle = it.getString("title"),
                            episodeIds = List(ids.length()) { index -> ids.getLong(index) },
                            notify = it.getBoolean("notify"),
                            autoDownload = it.getBoolean("autoDownload"),
                        )
                    },
                    moved = root.getJSONArray(MOVED).let { moved -> List(moved.length()) { moved.getString(it) } },
                )
                BOOKS -> NewContent.Books(
                    root.getJSONArray(BOOK_LIST).objects().map {
                        val show = if (it.has("showId")) NewShow(it.getString("showId"), it.getString("showTitle")) else null
                        NewBook(it.getLong("id"), it.getString("title"), it.getString("author"), show)
                    },
                )
                else -> null
            }
        } catch (e: JSONException) {
            null
        }
    }

    private fun <T> List<T>.toJsonArray(item: (T) -> JSONObject) = JSONArray().also { array -> forEach { array.put(item(it)) } }

    private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }

    private const val TYPE = "type"
    private const val MUSIC = "music"
    private const val PODCASTS = "podcasts"
    private const val BOOKS = "books"
    private const val ALBUMS = "albums"
    private const val EPISODES = "episodes"
    private const val MOVED = "moved"
    private const val BOOK_LIST = "books"
}
