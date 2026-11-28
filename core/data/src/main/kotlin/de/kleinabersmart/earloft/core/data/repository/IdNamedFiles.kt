package de.kleinabersmart.earloft.core.data.repository

import android.content.Context
import android.util.Log
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import java.io.File
import java.io.IOException

/**
 * Covers of albums and books are files named after IDs of the main database, and they outlive it. A main database created anew
 * hands the IDs out again: an album or book would show another one's cover and colors (review5 P29). Each such [directory]
 * remembers the main database it was filled for; if that is not the current one, its ID-named files go. A directory without
 * that note (filled before this check existed) is taken to belong to the current one. A database older than its creation marker
 * is noted as [UNMARKED], so that one created anew later still differs.
 * @return [directory], for use in an initializer.
 */
internal fun forgetIdFilesOfOtherDatabase(context: Context, directory: File): File {
    val created = File(context.noBackupFilesDir, EarLoftDatabase.CREATED_MARKER).takeIf { it.exists() }?.lastModified()?.toString()
        ?: UNMARKED
    val note = File(directory, DATABASE_NOTE)
    // In an initializer: a full disk must not make every later cover access throw.
    try {
        val filledFor = note.takeIf { it.isFile }?.readText()?.trim()
        if (filledFor == created) return directory
        if (filledFor != null) directory.listFiles { file -> ID_NAME.matches(file.name) }?.forEach { it.delete() }
        if (directory.mkdirs() || directory.isDirectory) note.writeText(created)
    } catch (e: IOException) {
        Log.w("IdNamedFiles", "Database note of $directory not kept", e)
    }
    return directory
}

/** The note of a main database from before its creation marker existed. */
private const val UNMARKED = "unmarked"

/** Kept by every clean-up of these directories. */
internal const val DATABASE_NOTE = ".database"

/** `42.jpg`, `42.img`, `42.old` and their `.part` files; covers stored by address (`u….img`) stay. */
private val ID_NAME = Regex("""\d+\.(jpg|img|old)(\.part)?""")
