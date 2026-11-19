package de.kleinabersmart.earloft.wear.sync

import com.google.android.gms.wearable.DataClient
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.earloft.core.data.repository.PlayReporter
import de.kleinabersmart.earloft.core.data.repository.SourcePreference
import de.kleinabersmart.earloft.core.database.dao.LibraryDao
import de.kleinabersmart.earloft.core.model.SourceType
import de.kleinabersmart.earloft.core.wearsync.WearCodec
import de.kleinabersmart.earloft.core.wearsync.WearPaths
import de.kleinabersmart.earloft.core.wearsync.WearPlayDto
import de.kleinabersmart.earloft.core.wearsync.WearPlaysDto
import de.kleinabersmart.earloft.core.wearsync.putPayload
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends every play to the phone as a separate DataItem `/earloft/plays/<uuid>` (Wear concept 10). Play services
 * keep it until the phone is reachable; the phone imports it and deletes it again. Packages with several
 * plays do not exist yet – one track per package keeps the loss on a crash at zero.
 */
@Singleton
class WatchPlayReporter @Inject constructor(
    private val dataClient: DataClient,
    private val libraryDao: LibraryDao,
) : PlayReporter {

    override suspend fun report(songId: Long, playedAt: Long, listenedMs: Long, live: Boolean) {
        val sources = libraryDao.getPlayableSources(songId)
        // The uid of a server song is the same on phone and watch. For the pseudo source `phone` the
        // `remoteId` is the uid on the phone (`local:39`, Wear concept 7.1); the phone does not know `phone:local:39`.
        val songUid = sources.filter { it.sourceType.isServer }.minWithOrNull(SourcePreference)?.uid
            ?: sources.firstOrNull { it.sourceType == SourceType.PHONE }?.remoteId
            ?: return
        val packet = WearPlaysDto(UUID.randomUUID().toString(), listOf(WearPlayDto(songUid, playedAt, listenedMs)))
        dataClient.putPayload(WearPaths.plays(packet.packetId), WearCodec.encode(packet))
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal interface WatchPlayReporterModule {
    @Binds
    fun bindReporter(reporter: WatchPlayReporter): PlayReporter
}
