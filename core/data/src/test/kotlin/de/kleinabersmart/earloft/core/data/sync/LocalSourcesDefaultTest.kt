package de.kleinabersmart.earloft.core.data.sync

import android.app.Application
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.datasource.local.AudioPermission
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class LocalSourcesDefaultTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val books = booleanPreferencesKey("local_books")
    private val podcasts = booleanPreferencesKey("local_podcasts")
    private val migration = LocalSourcesDefault(context, listOf(books, podcasts))

    @Test
    fun `without the permission untouched switches are off`() = runTest {
        val migrated = migration.migrate(emptyPreferences())

        assertEquals(false, migrated[books])
        assertEquals(false, migrated[podcasts])
    }

    @Test
    fun `with the permission untouched switches stay on`() = runTest {
        shadowOf(context).grantPermissions(AudioPermission.name)

        val migrated = migration.migrate(emptyPreferences())

        assertEquals(true, migrated[books])
        assertEquals(true, migrated[podcasts])
    }

    @Test
    fun `a switch set by the user keeps its value`() = runTest {
        shadowOf(context).grantPermissions(AudioPermission.name)

        val migrated = migration.migrate(preferencesOf(books to false))

        assertEquals(false, migrated[books])
        assertEquals(true, migrated[podcasts])
    }

    @Test
    fun `runs only while a switch has no value`() = runTest {
        assertTrue(migration.shouldMigrate(preferencesOf(books to true)))
        assertFalse(migration.shouldMigrate(preferencesOf(books to true, podcasts to false)))
    }
}
