package keinabersmart.kleinaberlaut.wear.sync

import com.google.android.gms.wearable.DataClient
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import keinabersmart.kleinaberlaut.core.data.repository.PlayReporter
import keinabersmart.kleinaberlaut.core.data.repository.SourcePreference
import keinabersmart.kleinaberlaut.core.database.dao.LibraryDao
import keinabersmart.kleinaberlaut.core.model.SourceType
import keinabersmart.kleinaberlaut.core.wearsync.WearCodec
import keinabersmart.kleinaberlaut.core.wearsync.WearPaths
import keinabersmart.kleinaberlaut.core.wearsync.WearPlayDto
import keinabersmart.kleinaberlaut.core.wearsync.WearPlaysDto
import keinabersmart.kleinaberlaut.core.wearsync.putPayload
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schickt jede Wiedergabe als eigenes DataItem `/kal/plays/<uuid>` ans Handy (Wear-Konzept 10). Die Play-Dienste
 * halten es, bis das Handy erreichbar ist; das Handy importiert es und löscht es wieder. Pakete mit mehreren
 * Wiedergaben gibt es noch nicht – ein Titel pro Paket hält den Verlust bei einem Absturz auf null.
 */
@Singleton
class WatchPlayReporter @Inject constructor(
    private val dataClient: DataClient,
    private val libraryDao: LibraryDao,
) : PlayReporter {

    override suspend fun report(songId: Long, playedAt: Long, listenedMs: Long) {
        val sources = libraryDao.getPlayableSources(songId)
        // Die uid eines Server-Songs ist auf Handy und Uhr gleich. Bei der Pseudo-Quelle `phone` ist die
        // `remoteId` die uid am Handy (`local:39`, Wear-Konzept 7.1); `phone:local:39` kennt das Handy nicht.
        val songUid = sources.filter { it.sourceType == SourceType.SUBSONIC }.minWithOrNull(SourcePreference)?.uid
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
