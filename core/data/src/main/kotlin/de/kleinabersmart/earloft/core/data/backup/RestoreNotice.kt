package de.kleinabersmart.earloft.core.data.backup

import android.content.Context
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.data.source.DataSourceRegistry
import de.kleinabersmart.earloft.core.data.spoken.AudiobookshelfServers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Eingeschaltete Server, denen nach dem Wiederherstellen Passwort bzw. API-Key fehlen. */
data class MissingCredentials(val musicServers: Int, val audiobookServers: Int) {
    val total: Int get() = musicServers + audiobookServers
}

/** Was nach dem Wiederherstellen zu melden ist: [backup] nach einer Komplett-Sicherung (sonst ein Gerätewechsel). */
data class RestoreReport(val missing: MissingCredentials, val backup: FullBackupResult? = null)

/**
 * Der Hinweis nach dem Wiederherstellen (Review K9): Server kommen ohne Passwort bzw. API-Key zurück, die liegen nur
 * verschlüsselt auf dem alten Gerät und gehören auch nicht in die Komplett-Sicherung. Das Zurückspielen nach einem
 * Gerätewechsel läuft beim Prozessstart, oft bevor die Oberfläche steht; deshalb merkt eine Datei in `noBackupFilesDir` den
 * Hinweis vor, bis die Oberfläche ihn einmal gezeigt hat. Nach einer Komplett-Sicherung gehört er zur Ergebnis-Meldung.
 */
@Singleton
class RestoreNotice @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val registry: Lazy<DataSourceRegistry>,
    private val audiobookshelf: Lazy<AudiobookshelfServers>,
) {
    private val marker = File(context.noBackupFilesDir, MARKER)
    private val restored = MutableStateFlow(0)
    private val backups = Channel<FullBackupResult>(Channel.BUFFERED)

    /**
     * Einmal je Zurückspielen, sobald die Oberfläche lauscht. Nach einem Gerätewechsel nur, wenn noch etwas fehlt (hat der
     * Nutzer schon alles neu eingegeben, kommt nichts); nach einer Komplett-Sicherung immer, mit ihrem Ergebnis.
     */
    val reports: Flow<RestoreReport> = merge(
        restored.mapNotNull { take() }.map { RestoreReport(it) },
        backups.receiveAsFlow().map { RestoreReport(missing(), it) },
    )

    /** Nach dem Zurückspielen von Servern beim Gerätewechsel aufrufen. */
    internal suspend fun serversRestored() {
        withContext(Dispatchers.IO) { marker.createNewFile() }
        restored.value++
    }

    /** Nach dem Einspielen einer Komplett-Sicherung. */
    internal suspend fun backupRestored(result: FullBackupResult) = backups.send(result)

    private suspend fun take(): MissingCredentials? {
        if (!withContext(Dispatchers.IO) { marker.delete() }) return null
        return missing().takeIf { it.total > 0 }
    }

    private suspend fun missing() = MissingCredentials(
        musicServers = registry.get().withoutPassword().size,
        audiobookServers = audiobookshelf.get().withoutKey().size,
    )

    private companion object {
        const val MARKER = "restore_notice_pending"
    }
}
