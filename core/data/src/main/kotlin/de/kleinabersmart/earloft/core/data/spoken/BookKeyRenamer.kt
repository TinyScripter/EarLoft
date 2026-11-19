package de.kleinabersmart.earloft.core.data.spoken

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import de.kleinabersmart.earloft.core.data.podcast.EpisodeQueue

/**
 * Anyone who stores book keys ([BookKeys]) in a file rewrites them when episodes get a new identifier
 * (switching a subscription's source, concept 12.10). The `bookId`s stay the same.
 */
interface BookKeyRenamer {
    /** [renames]: old key → new key; other entries stay. */
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
