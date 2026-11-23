package de.kleinabersmart.earloft.core.wearsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileChannelTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `channel without offset keeps the old path`() {
        assertEquals("/earloft/file/local:102", WearPaths.fileChannel("local:102", from = 0))
        assertEquals(FileChannelRequest("local:102", 0), WearPaths.parseFileChannel("/earloft/file/local:102"))
    }

    @Test
    fun `offset comes before the id, even if the id contains slashes`() {
        val uid = WearEpisodeFile.uid("f1", "https://example.org/episode/1")
        val path = WearPaths.fileChannel(uid, from = 5_000)
        assertEquals("/earloft/file-from/5000/$uid", path)
        assertEquals(FileChannelRequest(uid, 5_000), WearPaths.parseFileChannel(path))
        assertEquals(FileChannelRequest("a/b", 7), WearPaths.parseFileChannel("/earloft/file-from/7/a/b"))
    }

    @Test
    fun `foreign and broken paths are not accepted`() {
        assertNull(WearPaths.parseFileChannel("/earloft/plays/123"))
        assertNull(WearPaths.parseFileChannel("/earloft/file-from/x/local:1"))
        assertNull(WearPaths.parseFileChannel("/earloft/file-from/-3/local:1"))
        assertNull(WearPaths.parseFileChannel("/earloft/file-from/12"))
    }

    @Test
    fun `a malformed file id is no file, not a crash`() {
        assertNull(WearEpisodeFile.parse("episode:%zz:guid"))
        assertNull(WearBookFile.parse("book:abs:%:0"))
        assertNull(WearCoverFile.parse("cover:%g1:x"))
        assertNull(WearThumbnailFile.parse("thumb:feed:%"))
        assertEquals("a b" to "c/d", WearEpisodeFile.parse(WearEpisodeFile.uid("a b", "c/d")))
    }

    @Test
    fun `partial file with known length is resumed`() {
        val part = folder.newFile("a.phone.part").apply { writeBytes(ByteArray(300)) }
        val length = folder.newFile("a.phone.part.len").apply { writeText("1000") }
        assertEquals(300L, resumeOffset(part, length))
    }

    @Test
    fun `without length, empty or already complete the transfer starts over`() {
        val part = folder.newFile("b.phone.part").apply { writeBytes(ByteArray(300)) }
        val length = folder.root.resolve("b.phone.part.len")
        assertEquals(0L, resumeOffset(part, length))

        length.writeText("broken")
        assertEquals(0L, resumeOffset(part, length))

        length.writeText("300")
        assertEquals(0L, resumeOffset(part, length))

        part.writeBytes(ByteArray(0))
        length.writeText("1000")
        assertEquals(0L, resumeOffset(part, length))
    }
}
