package de.kleinabersmart.earloft.wear.download

import android.net.Network
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.earloft.core.data.repository.ArtworkRepository
import de.kleinabersmart.earloft.core.data.spoken.AudiobookshelfServers
import de.kleinabersmart.earloft.core.data.spoken.BookCovers
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.database.entity.DownloadEntity
import de.kleinabersmart.earloft.core.download.BookDownloader
import de.kleinabersmart.earloft.core.download.DownloadOutcome
import de.kleinabersmart.earloft.core.download.DownloadProfile
import de.kleinabersmart.earloft.core.download.Downloader
import de.kleinabersmart.earloft.core.download.EpisodeDownloader
import de.kleinabersmart.earloft.wear.sync.CredentialsRequester
import de.kleinabersmart.earloft.wear.sync.PhoneReturn
import javax.inject.Inject

/** Progress of a transfer: bytes done and, if known, the total. */
internal typealias TransferProgress = suspend (bytesDone: Long, bytesTotal: Long?) -> Unit

/**
 * The ways out of the [WearDownloadWorker]: Wi-Fi, the servers, the phone and the image sources. Everything the worker
 * decides on top of them (failed attempts, retries, the way via the phone, storage) is tested against a fake of this
 * interface, with the real database, files and [WatchState] (review2 section 5).
 *
 * Like the classes behind it, every transfer updates the state of its entry itself (running, done, failed).
 */
internal interface WearDownloadEdges {
    /** @return `null` if no Wi-Fi became available. */
    suspend fun acquireWifi(): WifiLease?

    /** Whether the watch knows the server of song [uid] (credentials present). */
    suspend fun knowsSource(uid: String): Boolean

    suspend fun downloadSong(download: DownloadEntity, profile: DownloadProfile, network: Network, onProgress: TransferProgress): DownloadOutcome

    suspend fun downloadEpisode(track: BookTrackEntity, network: Network, onProgress: TransferProgress): DownloadOutcome

    suspend fun downloadBookTrack(track: BookTrackEntity, network: Network, onProgress: TransferProgress): DownloadOutcome

    /** Whether the watch has the key for Audiobookshelf server [serverId]. */
    suspend fun hasBookServer(serverId: String): Boolean

    /** Audiobookshelf servers whose access token expires within [withinSeconds]. */
    suspend fun expiringBookKeys(withinSeconds: Long): List<String>

    /** Asks the phone for fresh credentials of [sourceIds]; the answer arrives later. */
    suspend fun requestCredentials(sourceIds: Collection<String>)

    /** Asks the phone to say when it is back ([PhoneReturn]). */
    suspend fun announcePhoneWait()

    suspend fun songFromPhone(download: DownloadEntity, onProgress: TransferProgress): DownloadOutcome

    suspend fun episodeFromPhone(track: BookTrackEntity, onProgress: TransferProgress): DownloadOutcome

    suspend fun bookTrackFromPhone(track: BookTrackEntity, onProgress: TransferProgress): DownloadOutcome

    /** The image of an episode or book from the phone; `null` if it has none or is unreachable. */
    suspend fun coverFromPhone(bookId: Long): ByteArray?

    /** Whether the image of [bookId] is cached (loading it if possible). */
    suspend fun ensureBookCover(bookId: Long): Boolean

    suspend fun storeBookCover(bookId: Long, bytes: ByteArray)

    /** Caches the cover of [albumId] as a file; `false` if there is none. */
    suspend fun cacheAlbumCover(albumId: Long, sizePx: Int): Boolean
}

internal class WearDownloadEdgesImpl @Inject constructor(
    private val wifi: WifiNetwork,
    private val downloader: Downloader,
    private val episodeDownloader: EpisodeDownloader,
    private val bookDownloader: BookDownloader,
    private val audiobookshelf: AudiobookshelfServers,
    private val credentials: CredentialsRequester,
    private val phoneReturn: PhoneReturn,
    private val phone: PhoneFileFetcher,
    private val bookCovers: BookCovers,
    private val artwork: ArtworkRepository,
) : WearDownloadEdges {
    override suspend fun acquireWifi(): WifiLease? = wifi.acquire()

    override suspend fun knowsSource(uid: String): Boolean = downloader.knowsSource(uid)

    override suspend fun downloadSong(download: DownloadEntity, profile: DownloadProfile, network: Network, onProgress: TransferProgress) =
        downloader.download(download, profile, network, onProgress)

    override suspend fun downloadEpisode(track: BookTrackEntity, network: Network, onProgress: TransferProgress) =
        episodeDownloader.download(track, network, onProgress)

    override suspend fun downloadBookTrack(track: BookTrackEntity, network: Network, onProgress: TransferProgress) =
        bookDownloader.download(track, network, onProgress)

    override suspend fun hasBookServer(serverId: String): Boolean = audiobookshelf.server(serverId) != null

    override suspend fun expiringBookKeys(withinSeconds: Long): List<String> = audiobookshelf.withExpiringToken(withinSeconds)

    override suspend fun requestCredentials(sourceIds: Collection<String>) {
        credentials.request(sourceIds)
    }

    override suspend fun announcePhoneWait() {
        phoneReturn.announce()
    }

    override suspend fun songFromPhone(download: DownloadEntity, onProgress: TransferProgress) = phone.fetch(download, onProgress)

    override suspend fun episodeFromPhone(track: BookTrackEntity, onProgress: TransferProgress) = phone.fetchEpisode(track, onProgress)

    override suspend fun bookTrackFromPhone(track: BookTrackEntity, onProgress: TransferProgress) = phone.fetchBookTrack(track, onProgress)

    override suspend fun coverFromPhone(bookId: Long): ByteArray? = phone.fetchCover(bookId)

    override suspend fun ensureBookCover(bookId: Long): Boolean = bookCovers.ensureCached(bookId)

    override suspend fun storeBookCover(bookId: Long, bytes: ByteArray) {
        bookCovers.store(bookId, bytes)
    }

    override suspend fun cacheAlbumCover(albumId: Long, sizePx: Int): Boolean = artwork.cacheAlbumCover(albumId, sizePx)
}

@Module
@InstallIn(SingletonComponent::class)
internal interface WearDownloadEdgesModule {
    @Binds
    fun bindEdges(edges: WearDownloadEdgesImpl): WearDownloadEdges
}
