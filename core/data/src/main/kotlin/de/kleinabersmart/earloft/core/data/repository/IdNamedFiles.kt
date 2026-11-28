package de.kleinabersmart.earloft.core.data.repository

import android.content.Context
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import java.io.File

/**
 * Covers of albums and books are files named after IDs of the main database, and they outlive it. A main database created anew
 * hands the IDs out again: an album or book would show another one's cover and colors (review5 P29). Each such [directory]
 * remembers the main database it was filled for; if that is not the current one, its ID-named files go. A directory without
 * that note (filled before this check existed) is taken to belong to the current one.
 * @return [directory], for use in an initializer.
 */
internal fun forgetIdFilesOfOtherDatabase(context: Context, directory: File): File {
    val created = File(context.noBackupFilesDir, EarLoftDatabase.CREATED_MARKER).takeIf { it.exists() }?.lastModified()?.toString()
        ?: return directory
    val note = File(directory, DATABASE_NOTE)
    val filledFor = note.takeIf { it.isFile }?.readText()?.trim()
    if (filledFor == created) return directory
    if (filledFor != null) directory.listFiles { file -> ID_NAME.matches(file.name) }?.forEach { it.delete() }
    if (directory.mkdirs() || directory.isDirectory) note.writeText(created)
    return directory
}

/** Kept by every clean-up of these directories. */
internal const val DATABASE_NOTE = ".database"

/** `42.jpg`, `42.img`, `42.old` and their `.part` files; covers stored by address (`u….img`) stay. */
private val ID_NAME = Regex("""\d+\.(jpg|img|old)(\.part)?""")
