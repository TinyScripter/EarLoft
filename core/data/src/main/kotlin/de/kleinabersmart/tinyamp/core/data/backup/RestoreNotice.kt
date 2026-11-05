package de.kleinabersmart.tinyamp.core.data.backup

import android.content.Context
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.tinyamp.core.data.source.DataSourceRegistry
import de.kleinabersmart.tinyamp.core.data.spoken.AudiobookshelfServers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Eingeschaltete Server, denen nach dem Wiederherstellen Passwort bzw. API-Key fehlen. */
data class MissingCredentials(val musicServers: Int, val audiobookServers: Int) {
    val total: Int get() = musicServers + audiobookServers
}

/**
 * Der Hinweis nach einem Gerätewechsel (Review K9): Server kommen ohne Passwort bzw. API-Key zurück, die liegen nur
 * verschlüsselt auf dem alten Gerät. Das Zurückspielen läuft beim Prozessstart, oft bevor die Oberfläche steht; deshalb
 * merkt eine Datei in `noBackupFilesDir` den Hinweis vor, bis die Oberfläche ihn einmal gezeigt hat.
 */
@Singleton
class RestoreNotice @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val registry: Lazy<DataSourceRegistry>,
    private val audiobookshelf: Lazy<AudiobookshelfServers>,
) {
    private val marker = File(context.noBackupFilesDir, MARKER)
    private val restored = MutableStateFlow(0)

    /**
     * Einmal je Zurückspielen, sobald die Oberfläche lauscht: was noch fehlt. Hat der Nutzer schon alles neu eingegeben,
     * kommt nichts.
     */
    val missingCredentials: Flow<MissingCredentials> = restored.mapNotNull { take() }

    /** Nach dem Zurückspielen von Servern aufrufen. */
    internal suspend fun serversRestored() {
        withContext(Dispatchers.IO) { marker.createNewFile() }
        restored.value++
    }

    private suspend fun take(): MissingCredentials? {
        if (!withContext(Dispatchers.IO) { marker.delete() }) return null
        return MissingCredentials(
            musicServers = registry.get().withoutPassword().size,
            audiobookServers = audiobookshelf.get().withoutKey().size,
        ).takeIf { it.total > 0 }
    }

    private companion object {
        const val MARKER = "restore_notice_pending"
    }
}
