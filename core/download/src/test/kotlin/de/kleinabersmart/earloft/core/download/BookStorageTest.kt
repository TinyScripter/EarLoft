package de.kleinabersmart.earloft.core.download

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookStorageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var storage: BookStorage

    @Before
    fun setUp() {
        storage = BookStorage(tempFolder.newFolder("books"))
    }

    @Test
    fun `the usual name is kept while no other file of the book holds it`() {
        val file = storage.freeFileFor(7, 0, "audio/mpeg", taken = listOf(storage.fileFor(7, 1, "audio/mpeg").absolutePath))
        assertEquals(storage.fileFor(7, 0, "audio/mpeg"), file)
    }

    @Test
    fun `a name another file of the book still holds is not reused`() {
        // Datei A war Index 0 und ist auf dem Server auf Index 1 gerückt, ihr Pfad blieb; die neue Datei auf Index 0 lädt jetzt.
        val loadedA = storage.fileFor(7, 0, "audio/mpeg").absolutePath
        val loadedB = storage.fileFor(7, 1, "audio/mpeg").absolutePath
        val file = storage.freeFileFor(7, 0, "audio/mpeg", taken = listOf(loadedA, loadedB))
        assertEquals("book-7-0-2.mp3", file.name)

        val third = storage.freeFileFor(7, 0, "audio/mpeg", taken = listOf(loadedA, file.absolutePath))
        assertEquals("book-7-0-3.mp3", third.name)
    }
}
