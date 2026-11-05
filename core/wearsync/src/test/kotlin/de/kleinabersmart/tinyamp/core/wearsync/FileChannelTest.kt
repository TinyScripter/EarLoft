package de.kleinabersmart.tinyamp.core.wearsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileChannelTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `Kanal ohne Versatz bleibt der alte Pfad`() {
        assertEquals("/tinyamp/file/local:102", WearPaths.fileChannel("local:102", from = 0))
        assertEquals(FileChannelRequest("local:102", 0), WearPaths.parseFileChannel("/tinyamp/file/local:102"))
    }

    @Test
    fun `Versatz steht vor der Kennung, auch wenn diese Schraegstriche enthaelt`() {
        val uid = WearEpisodeFile.uid("f1", "https://example.org/folge/1")
        val path = WearPaths.fileChannel(uid, from = 5_000)
        assertEquals("/tinyamp/file-from/5000/$uid", path)
        assertEquals(FileChannelRequest(uid, 5_000), WearPaths.parseFileChannel(path))
        assertEquals(FileChannelRequest("a/b", 7), WearPaths.parseFileChannel("/tinyamp/file-from/7/a/b"))
    }

    @Test
    fun `Fremde und kaputte Pfade werden nicht angenommen`() {
        assertNull(WearPaths.parseFileChannel("/tinyamp/plays/123"))
        assertNull(WearPaths.parseFileChannel("/tinyamp/file-from/x/local:1"))
        assertNull(WearPaths.parseFileChannel("/tinyamp/file-from/-3/local:1"))
        assertNull(WearPaths.parseFileChannel("/tinyamp/file-from/12"))
    }

    @Test
    fun `Teildatei mit bekannter Laenge wird fortgesetzt`() {
        val part = folder.newFile("a.phone.part").apply { writeBytes(ByteArray(300)) }
        val length = folder.newFile("a.phone.part.len").apply { writeText("1000") }
        assertEquals(300L, resumeOffset(part, length))
    }

    @Test
    fun `Ohne Laenge, leer oder schon vollstaendig beginnt die Uebertragung von vorn`() {
        val part = folder.newFile("b.phone.part").apply { writeBytes(ByteArray(300)) }
        val length = folder.root.resolve("b.phone.part.len")
        assertEquals(0L, resumeOffset(part, length))

        length.writeText("kaputt")
        assertEquals(0L, resumeOffset(part, length))

        length.writeText("300")
        assertEquals(0L, resumeOffset(part, length))

        part.writeBytes(ByteArray(0))
        length.writeText("1000")
        assertEquals(0L, resumeOffset(part, length))
    }
}
