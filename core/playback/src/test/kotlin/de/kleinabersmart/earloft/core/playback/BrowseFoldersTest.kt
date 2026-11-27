package de.kleinabersmart.earloft.core.playback

import de.kleinabersmart.earloft.core.model.SpokenMode
import de.kleinabersmart.earloft.core.model.SpokenMode.BOOKS
import de.kleinabersmart.earloft.core.model.SpokenMode.MUSIC
import de.kleinabersmart.earloft.core.model.SpokenMode.PODCASTS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseFoldersTest {

    private fun tabs(vararg areas: SpokenMode) = rootFolders(areas.toList()).map { it.mediaId }

    @Test
    fun `all areas give recent and one tab per area`() {
        assertEquals(listOf(MediaIds.HOME, MediaIds.MUSIC, MediaIds.PODCASTS, MediaIds.BOOKS), tabs(MUSIC, PODCASTS, BOOKS))
    }

    @Test
    fun `two areas leave out the third`() {
        assertEquals(listOf(MediaIds.HOME, MediaIds.MUSIC, MediaIds.BOOKS), tabs(MUSIC, BOOKS))
        assertEquals(listOf(MediaIds.HOME, MediaIds.PODCASTS, MediaIds.BOOKS), tabs(PODCASTS, BOOKS))
    }

    @Test
    fun `music alone keeps the tabs it always had`() {
        assertEquals(listOf(MediaIds.RECENT, MediaIds.LIBRARY, MediaIds.PLAYLISTS, MediaIds.FAVORITES), tabs(MUSIC))
    }

    @Test
    fun `a single spoken area shows its folders as tabs`() {
        assertEquals(
            listOf(MediaIds.EPISODES_CONTINUE, MediaIds.EPISODES_NEW, MediaIds.FEEDS, MediaIds.EPISODES_DOWNLOADED),
            tabs(PODCASTS),
        )
        assertEquals(listOf(MediaIds.BOOKS_CONTINUE, MediaIds.BOOKS_ALL, MediaIds.AUTHORS, MediaIds.SERIES), tabs(BOOKS))
    }

    @Test
    fun `never more than four tabs`() {
        val combinations = listOf(listOf(MUSIC), listOf(PODCASTS), listOf(BOOKS), listOf(MUSIC, PODCASTS), SpokenMode.entries)
        for (areas in combinations) assertTrue(areas.toString(), rootFolders(areas).size <= 4)
    }

    @Test
    fun `every tab and area folder has a white symbol`() {
        val combinations = listOf(listOf(MUSIC), listOf(PODCASTS), listOf(BOOKS), listOf(MUSIC, PODCASTS), SpokenMode.entries)
        for (areas in combinations) {
            val folders = rootFolders(areas) + areas.flatMap(::areaChildren)
            assertTrue(areas.toString(), folders.all { it.icon != null })
        }
    }

    @Test
    fun `no tab belongs to a hidden area`() {
        for (hidden in SpokenMode.entries) {
            val areas = SpokenMode.entries - hidden
            val ids = rootFolders(areas).map { it.mediaId } + areas.flatMap { area -> areaChildren(area).map { it.mediaId } }
            assertTrue(ids.none { MediaIds.areaOf(it) == hidden })
        }
    }

    @Test
    fun `folders of an area belong to it`() {
        for (area in SpokenMode.entries) {
            assertEquals(area, MediaIds.areaOf(areaFolder(area).mediaId))
            assertEquals(area, areaOfTab(areaFolder(area).mediaId))
            assertTrue(areaChildren(area).all { MediaIds.areaOf(it.mediaId) == area })
        }
    }
}
