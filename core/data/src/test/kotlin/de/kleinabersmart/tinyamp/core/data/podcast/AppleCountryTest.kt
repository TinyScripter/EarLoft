package de.kleinabersmart.tinyamp.core.data.podcast

import de.kleinabersmart.tinyamp.core.data.podcast.PodcastSettings.Companion.countryFromLocales
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/** Robolectric für `android.icu` (übliches Land einer Sprache). */
@RunWith(RobolectricTestRunner::class)
class AppleCountryTest {

    private fun tags(vararg tags: String) = tags.map(Locale::forLanguageTag)

    @Test
    fun `the app language decides and takes its region from a matching system language`() {
        assertEquals("DE", countryFromLocales(tags("de"), tags("en-US")))
        assertEquals("AT", countryFromLocales(tags("de"), tags("en-US", "de-AT")))
        assertEquals("CH", countryFromLocales(tags("de-CH"), tags("de-DE")))
        assertEquals("US", countryFromLocales(tags("en"), tags("de-DE")))
    }

    @Test
    fun `without an app language the first system region counts`() {
        assertEquals("DE", countryFromLocales(emptyList(), tags("de-DE", "en-US")))
        assertEquals("FR", countryFromLocales(emptyList(), tags("fr")))
        assertEquals("NL", countryFromLocales(emptyList(), tags("es-419", "nl-NL")))
    }

    @Test
    fun `nothing usable falls back to the united states`() {
        assertEquals("US", countryFromLocales(emptyList(), emptyList()))
        assertEquals("US", countryFromLocales(emptyList(), listOf(Locale.ROOT)))
    }
}
