package de.kleinabersmart.earloft.ui.setup

import de.kleinabersmart.earloft.core.model.SpokenMode
import de.kleinabersmart.earloft.navigation.SetupPage
import org.junit.Assert.assertEquals
import org.junit.Test

class SetupPagesTest {

    /** Die Benachrichtigungsseite hängt von der Android-Version ab und bleibt hier außen vor. */
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
}
