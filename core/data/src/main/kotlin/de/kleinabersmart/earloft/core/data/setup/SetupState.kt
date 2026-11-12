package de.kleinabersmart.earloft.core.data.setup

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.data.backup.UserDataBackup
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.core.data.sync.LibrarySettings
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.dao.PodcastDao
import de.kleinabersmart.earloft.core.database.dao.SourceDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import javax.inject.Inject
import javax.inject.Singleton

/** Gesichert (anders als `noBackupFilesDir`): Ein wiederhergestelltes Gerät bringt „schon eingerichtet“ mit. */
private val Context.setupDataStore: DataStore<Preferences> by preferencesDataStore(name = "setup")

/**
 * Ob der Einrichtungsassistent beim Start erscheint (Konzept 15).
 *
 * Der Merker [KEY_DONE] fehlt nur beim ersten Start mit dieser Version. Dann entscheidet der Inhalt, nachdem gesicherte
 * Nutzerdaten zurückgespielt sind: Gibt es schon einen Server, ein Abo oder eine eingeschaltete Quelle auf dem Gerät
 * (Update, Wiederherstellung), gilt die Einrichtung still als erledigt; sonst erscheint der Assistent, bis er beendet wird.
 */
@Singleton
class SetupState internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val awaitRestore: suspend () -> Unit,
    private val hasContent: suspend () -> Boolean,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        backup: UserDataBackup,
        sourceDao: SourceDao,
        bookDao: BookDao,
        podcastDao: PodcastDao,
        librarySettings: LibrarySettings,
        spokenSettings: SpokenSettings,
    ) : this(
        dataStore = context.setupDataStore,
        awaitRestore = backup::awaitRestore,
        hasContent = {
            sourceDao.getServers().isNotEmpty() || bookDao.servers().isNotEmpty() || podcastDao.feeds().isNotEmpty() ||
                librarySettings.useLocalMedia.first() || spokenSettings.useLocalBooks.first() ||
                spokenSettings.useLocalPodcasts.first()
        },
    )

    /** `true`, solange der Assistent beim Start erscheinen soll. Liefert erst, wenn das feststeht. */
    val pending: Flow<Boolean> = dataStore.data.map { it[KEY_DONE] }.distinctUntilChanged().transform { done ->
        if (done != null) emit(!done) else decide()
    }

    /** Assistent beendet, ob über „Zur App“ oder „Überspringen“; er erscheint nicht mehr von selbst. */
    suspend fun finish() {
        dataStore.edit { it[KEY_DONE] = true }
    }

    /** Schreibt die Entscheidung; [pending] liefert sie mit der Änderung. */
    private suspend fun decide() {
        awaitRestore()
        val done = hasContent()
        dataStore.edit { if (KEY_DONE !in it) it[KEY_DONE] = done }
    }

    private companion object {
        val KEY_DONE = booleanPreferencesKey("setup_done")
    }
}
