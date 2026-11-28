package de.kleinabersmart.earloft.core.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Covers named after IDs of the main database go when it is created anew (review5 P29). */
@RunWith(RobolectricTestRunner::class)
class IdNamedFilesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory = File(context.noBackupFilesDir, "covers-test")
    private val marker = File(context.noBackupFilesDir, EarLoftDatabase.CREATED_MARKER)

    private fun databaseCreatedAt(time: Long) {
        marker.createNewFile()
        marker.setLastModified(time)
    }

    private fun files() = directory.list()!!.filterNot { it.startsWith(".") }.toSet()

    @Test
    fun `a main database created anew takes the covers named after its old ids along`() {
        databaseCreatedAt(1_000_000)
        directory.mkdirs()
        listOf("5.jpg", "7.img", "7.old", "u123.img").forEach { File(directory, it).writeText("x") }

        // Filled before this check existed: taken to belong to the current database.
        forgetIdFilesOfOtherDatabase(context, directory)
        assertEquals(setOf("5.jpg", "7.img", "7.old", "u123.img"), files())

        // Same database, next process: nothing happens.
        forgetIdFilesOfOtherDatabase(context, directory)
        assertEquals(4, files().size)

        // The main database was created anew: its IDs start over.
        databaseCreatedAt(2_000_000)
        forgetIdFilesOfOtherDatabase(context, directory)
        assertEquals("covers by address stay", setOf("u123.img"), files())
    }

    @Test
    fun `a database from before the creation marker counts as another one once it is created anew`() {
        marker.delete()
        directory.mkdirs()
        listOf("5.jpg", "u123.img").forEach { File(directory, it).writeText("x") }

        forgetIdFilesOfOtherDatabase(context, directory)
        forgetIdFilesOfOtherDatabase(context, directory)
        assertEquals(setOf("5.jpg", "u123.img"), files())

        databaseCreatedAt(2_000_000)
        forgetIdFilesOfOtherDatabase(context, directory)
        assertEquals(setOf("u123.img"), files())
    }
}
