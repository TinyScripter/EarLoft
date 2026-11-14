package de.kleinabersmart.earloft.core.network.podcast.ardsounds

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.OffsetDateTime

class ArdSoundsBooksTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var books: ArdSoundsBooks

    @Before
    fun setUp() {
        mockServer = MockWebServer().apply { start() }
        books = ArdSoundsBooks(ArdSoundsApi(OkHttpClient(), mockServer.url("/graphql")))
    }

    @After
    fun tearDown() {
        mockServer.close()
    }

    @Test
    fun `titles name author, title and narrator`() {
        assertEquals(ArdTitle("Katerina Poladjan", "Goldstrand", ""), parseArdTitle("Katerina Poladjan: Goldstrand"))
        assertEquals(
            ArdTitle("H. G. Wells", "Der Krieg der Welten", "Andreas Fröhlich"),
            parseArdTitle("H. G. Wells: \"Der Krieg der Welten\" | Gelesen von Andreas Fröhlich"),
        )
        assertEquals(ArdTitle("Alexandre Dumas", "Die Kameliendame", ""), parseArdTitle("Alexandre Dumas: Die Kameliendame - WDR 5 Hörbuch"))
        assertEquals(ArdTitle("Freya Marske", "A Marvellous Light", ""), parseArdTitle("“A Marvellous Light” von Freya Marske"))
        assertEquals(ArdTitle("Edgar Allan Poe", "Gruselgeschichten als Hörbücher", ""), parseArdTitle("Edgar Allan Poe: Gruselgeschichten als Hörbücher | WDR"))
        // Kein Name vor dem Doppelpunkt: alles ist Titel.
        assertEquals(ArdTitle("", "1938: The War of the Worlds", ""), parseArdTitle("1938: The War of the Worlds"))
        assertEquals(ArdTitle("", "Tatort: Kunst", ""), parseArdTitle("Tatort: Kunst"))
    }

    @Test
    fun `narrator from the description`() {
        assertEquals("Harald Schrott", narratorIn("Lesung mit Harald Schrott\nProduktion: MDR 2025"))
        assertEquals("Sylvester Groth", narratorIn("Es liest Sylvester Groth."))
        assertNull(narratorIn("Mit Musik von Bach"))
    }

    @Test
    fun `numbered titles`() {
        assertEquals(NumberedTitle("Katerina Poladjan: Goldstrand", 3, null, ""), numberedTitle("Katerina Poladjan: Goldstrand (3/9)"))
        assertEquals(NumberedTitle("Tyll", 3, null, "Minnesänger im kriegsverwüsteten Land"), numberedTitle("Tyll (3/8): Minnesänger im kriegsverwüsteten Land"))
        assertEquals(NumberedTitle("Hans Raithel: Der Schusterhans", 2, null, ""), numberedTitle("Hans Raithel: Der Schusterhans (2)"))
        assertEquals(NumberedTitle("Die Kameliendame", 1, "Folge", ""), numberedTitle("Die Kameliendame, Folge 1"))
        assertNull(numberedTitle("Der Krieg der Welten"))
        assertNull(numberedTitle("1001 Nacht"))
    }

    @Test
    fun `book keys group parts`() {
        fun key(title: String, groupId: String? = null, type: String? = null) =
            ardBookKey(ArdKeyInput("urn:ard:publication:1", title, groupId, type, 7263856))
        assertEquals("group:12976955", key("Späte Rache, Folge 8", "12976955", "MULTIPART"))
        // Staffeln sind Sammlungen: jeder Titel ein Buch.
        assertEquals("item:urn:ard:publication:1", key("Erich Loest: Haare | Gelesen von Gert Haucke", "99", "SEASON"))
        assertEquals("title:7263856:Tyll", key("Tyll (3/8): Minnesänger"))
        assertEquals("item:urn:ard:publication:1", key("Bram Stoker: Dracula"))
        assertEquals(ArdBookKey.Title(7263856, "Tyll: Teil"), ArdBookKey.parse("title:7263856:Tyll: Teil"))
        assertEquals(ArdBookKey.Group("urn:ard:show:aa"), ArdBookKey.parse("group:urn:ard:show:aa"))
        assertNull(ArdBookKey.parse("show:x"))
    }

    @Test
    fun `book collects the numbered parts in order and takes the stream when download is not allowed`() = runTest {
        mockServer.enqueue(
            MockResponse.Builder().body(
                """{"data":{"items":{"nodes":[
                    ${item("p2", "Goldstrand (2/2): Der Strand", 2, allowDownload = false, until = "2027-01-08T22:59:00Z")},
                    ${item("trailer", "Goldstrand – Trailer", null, allowDownload = true)},
                    ${item("p1", "Goldstrand (1/2)", 1, allowDownload = true, until = "2027-03-01T10:00:00Z")}
                ]}}}""",
            ).build(),
        )

        val book = books.book("group:urn:ard:show:gs")!!

        assertEquals("Katerina Poladjan", book.author)
        assertEquals("Goldstrand", book.title)
        assertEquals("Harald Schrott", book.narrator)
        assertEquals(ArdCategory.AUDIOBOOK, book.category)
        assertEquals(listOf("Teil 1", "Der Strand"), book.parts.map { it.title })
        assertEquals(listOf("https://dl.example/p1.mp3", "https://stream.example/p2.mp3"), book.parts.map { it.audioUrl })
        assertEquals("audio/mpeg", book.parts.first().mimeType)
        assertEquals(3_000_000L, book.durationMs)
        assertEquals(OffsetDateTime.parse("2027-01-08T22:59:00Z").toInstant().toEpochMilli(), book.availableUntil)
    }

    @Test
    fun `book without published parts is null`() = runTest {
        mockServer.enqueue(MockResponse.Builder().body("""{"data":{"items":{"nodes":[]}}}""").build())
        assertNull(books.book("item:urn:ard:publication:gone"))
    }

    private fun item(id: String, title: String, number: Int?, allowDownload: Boolean, until: String? = null): String = """
        {"id":"$id","coreId":"urn:ard:publication:$id","title":"$title","groupId":"urn:ard:show:gs","episodeNumber":${number ?: "null"},
         "maxGroupNumber":2,"duration":1500,"publishDate":"2026-11-0${number ?: 9}T10:00:00+01:00",
         "group":{"type":"MULTIPART","title":"Katerina Poladjan: Goldstrand","count":2},
         "audios":[{"url":"https://stream.example/$id.mp3","downloadUrl":${if (allowDownload) "\"https://dl.example/$id.mp3\"" else "null"},
                    "allowDownload":$allowDownload,"mimeType":"audio/mp3"}],
         "programSet":{"id":"1","coreId":"urn:ard:show:gs","title":"Katerina Poladjan: Goldstrand","description":"Lesung mit Harald Schrott",
                       "publicationService":{"title":"MDR KULTUR"},"editorialCategoryId":"42914713","editorialCategoriesList":[{"id":"42914713"}]},
         "coreDocument":{"availableTo":${until?.let { "\"$it\"" } ?: "null"}}}
    """.trimIndent()
}
