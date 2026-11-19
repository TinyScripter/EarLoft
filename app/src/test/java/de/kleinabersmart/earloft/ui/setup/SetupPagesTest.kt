package de.kleinabersmart.earloft.ui.setup

import de.kleinabersmart.earloft.core.model.SpokenMode
import de.kleinabersmart.earloft.navigation.SetupPage
import de.kleinabersmart.earloft.wear.WatchSetup
import org.junit.Assert.assertEquals
import org.junit.Test

class SetupPagesTest {

    /** The notification page depends on the Android version and is left out here. */
    private fun pages(vararg areas: SpokenMode, watch: Boolean = false) =
        visibleSetupPages(areas.toList(), watch) - SetupPage.NOTIFICATIONS

    @Test
    fun `all areas get their source page in order`() {
        assertEquals(
            listOf(
                SetupPage.WELCOME, SetupPage.AREAS, SetupPage.MUSIC, SetupPage.PODCASTS, SetupPage.BOOKS, SetupPage.DESIGN,
                SetupPage.DONE,
            ),
            pages(*SpokenMode.entries.toTypedArray()),
        )
    }

    @Test
    fun `switched off areas have no source page`() {
        assertEquals(
            listOf(SetupPage.WELCOME, SetupPage.AREAS, SetupPage.PODCASTS, SetupPage.DESIGN, SetupPage.DONE),
            pages(SpokenMode.PODCASTS),
        )
    }

    @Test
    fun `the watch page comes before the design page, only with a watch`() {
        assertEquals(
            listOf(SetupPage.WELCOME, SetupPage.AREAS, SetupPage.BOOKS, SetupPage.WATCH, SetupPage.DESIGN, SetupPage.DONE),
            pages(SpokenMode.BOOKS, watch = true),
        )
    }

    @Test
    fun `the page shown is always among the pages, also while the watch is still unknown`() {
        val pages = setupPages(listOf(SpokenMode.BOOKS), watch = null, current = SetupPage.WATCH) - SetupPage.NOTIFICATIONS

        assertEquals(listOf(SetupPage.WELCOME, SetupPage.AREAS, SetupPage.BOOKS, SetupPage.WATCH, SetupPage.DESIGN, SetupPage.DONE), pages)
        assertEquals(
            listOf(SetupPage.WELCOME, SetupPage.AREAS, SetupPage.BOOKS, SetupPage.DESIGN, SetupPage.DONE),
            setupPages(listOf(SpokenMode.BOOKS), watch = WatchSetup.NONE, current = SetupPage.BOOKS) - SetupPage.NOTIFICATIONS,
        )
    }
}
