package de.kleinabersmart.tinyamp.core.network.podcast.directory

import org.junit.Assert.assertEquals
import org.junit.Test

class DirectoryPodcastTest {

    @Test
    fun `feed keys ignore scheme, www and a trailing slash`() {
        assertEquals(feedKey("https://www.example.io/feed/mp3/"), feedKey("http://example.io/feed/mp3"))
        assertEquals("example.io:8080/feed?id=1", feedKey("https://Example.io:8080/feed/?id=1"))
    }

    @Test
    fun `results are interleaved and duplicates merged`() {
        val apple = listOf(hit("Both", "https://www.both.example/feed/", PodcastDirectory.APPLE), hit("A2", "https://a.example/2", PodcastDirectory.APPLE))
        val fyyd = listOf(
            hit("F1", "https://f.example/1", PodcastDirectory.FYYD),
            hit("Both (fyyd)", "http://both.example/feed", PodcastDirectory.FYYD, author = "Autor", image = "https://img"),
            hit("F3", "https://f.example/3", PodcastDirectory.FYYD),
        )

        val merged = mergeDirectoryResults(listOf(apple, fyyd))

        assertEquals(listOf("Both", "F1", "A2", "F3"), merged.map { it.title })
        val both = merged[0]
        assertEquals(setOf(PodcastDirectory.APPLE, PodcastDirectory.FYYD), both.directories)
        assertEquals("Autor", both.author)
        assertEquals("https://img", both.imageUrl)
        assertEquals("https://www.both.example/feed/", both.feedUrl)
    }

    @Test
    fun `a merged feed keeps the ard sounds alternative from any directory`() {
        val apple = listOf(hit("Talk", "https://www.fritz.de/talk.xml", PodcastDirectory.APPLE))
        val ard = listOf(hit("Talk", "https://fritz.de/talk.xml", PodcastDirectory.ARD_SOUNDS).copy(ardSoundsUrl = "ardsounds:urn:ard:show:5f"))

        val merged = mergeDirectoryResults(listOf(apple, ard)).single()

        assertEquals("ardsounds:urn:ard:show:5f", merged.ardSoundsUrl)
        assertEquals(setOf(PodcastDirectory.APPLE, PodcastDirectory.ARD_SOUNDS), merged.directories)
    }

    private fun hit(title: String, url: String, directory: PodcastDirectory, author: String = "", image: String? = null) =
        DirectoryPodcast(title, author, url, image, setOf(directory))
}
