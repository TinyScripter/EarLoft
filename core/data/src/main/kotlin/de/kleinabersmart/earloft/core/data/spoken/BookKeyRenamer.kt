package de.kleinabersmart.earloft.core.data.spoken

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import de.kleinabersmart.earloft.core.data.podcast.EpisodeQueue

/**
 * Wer Schlüssel von Büchern ([BookKeys]) in einer Datei speichert, schreibt sie um, wenn Folgen eine neue Kennung bekommen
 * (Quelle eines Abos wechseln, Konzept 12.10). Die `bookId`s bleiben dabei gleich.
 */
interface BookKeyRenamer {
    /** [renames]: alter Schlüssel → neuer Schlüssel; andere Einträge bleiben. */
    suspend fun renameBookKeys(renames: Map<String, String>)
}

@Module
@InstallIn(SingletonComponent::class)
internal interface BookKeyRenamerModule {
    @Binds
    @IntoSet
    fun episodeQueue(queue: EpisodeQueue): BookKeyRenamer

    @Binds
    fun keyMoves(follower: BookKeyFollower): BookKeyMoves
}
