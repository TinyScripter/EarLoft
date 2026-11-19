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

/** Backed up (unlike `noBackupFilesDir`): a restored device brings "already set up" along. */
private val Context.setupDataStore: DataStore<Preferences> by preferencesDataStore(name = "setup")

/**
 * Whether the setup wizard appears at startup (concept 15).
 *
 * The flag [KEY_DONE] is only missing on the first start with this version. Then the content decides, after backed-up
 * user data has been restored: if there already is a server, a subscription or an enabled on-device source
 * (update, restore), setup silently counts as done; otherwise the wizard appears until it is finished.
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

    /** `true` as long as the wizard should appear at startup. Only emits once that is determined. */
    val pending: Flow<Boolean> = dataStore.data.map { it[KEY_DONE] }.distinctUntilChanged().transform { done ->
        if (done != null) emit(!done) else decide()
    }

    /** Wizard finished, whether via "To the app" or "Skip"; it no longer appears on its own. */
    suspend fun finish() {
        dataStore.edit { it[KEY_DONE] = true }
    }

    /** Writes the decision; [pending] emits it with the change. */
    private suspend fun decide() {
        awaitRestore()
        val done = hasContent()
        dataStore.edit { if (KEY_DONE !in it) it[KEY_DONE] = done }
    }

    private companion object {
        val KEY_DONE = booleanPreferencesKey("setup_done")
    }
}
