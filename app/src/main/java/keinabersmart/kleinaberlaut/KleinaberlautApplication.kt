package keinabersmart.kleinaberlaut

import android.app.Application
import androidx.annotation.OptIn
import androidx.media3.cast.Cast
import androidx.media3.common.util.UnstableApi
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.crossfade
import dagger.hilt.android.HiltAndroidApp
import keinabersmart.kleinaberlaut.core.data.repository.ArtworkRepository
import keinabersmart.kleinaberlaut.core.data.di.ApplicationScope
import keinabersmart.kleinaberlaut.core.data.spoken.AuthorImages
import keinabersmart.kleinaberlaut.core.data.spoken.BookCovers
import keinabersmart.kleinaberlaut.core.data.podcast.PodcastRefresher
import keinabersmart.kleinaberlaut.core.data.spoken.SpokenRefresher
import keinabersmart.kleinaberlaut.core.data.sync.LibraryRefresher
import keinabersmart.kleinaberlaut.core.data.sync.SyncQueueStarter
import keinabersmart.kleinaberlaut.core.download.AutoDownloads
import keinabersmart.kleinaberlaut.core.download.BookDownloads
import keinabersmart.kleinaberlaut.core.download.DownloadRepository
import keinabersmart.kleinaberlaut.core.download.EpisodeDownloads
import keinabersmart.kleinaberlaut.ui.image.AlbumCoverFetcher
import keinabersmart.kleinaberlaut.ui.image.ArtistImageFetcher
import keinabersmart.kleinaberlaut.ui.image.AuthorImageFetcher
import keinabersmart.kleinaberlaut.ui.image.BookCoverFetcher
import keinabersmart.kleinaberlaut.wear.PhoneInfoPublisher
import keinabersmart.kleinaberlaut.wear.WearPublisher
import keinabersmart.kleinaberlaut.wear.WearRemotePublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class KleinaberlautApplication : Application(), SingletonImageLoader.Factory {

    @Inject lateinit var libraryRefresher: LibraryRefresher
    @Inject lateinit var spokenRefresher: SpokenRefresher
    @Inject lateinit var podcastRefresher: PodcastRefresher
    @Inject lateinit var bookCovers: BookCovers
    @Inject lateinit var authorImages: AuthorImages
    @Inject lateinit var artworkRepository: ArtworkRepository
    @Inject lateinit var downloadRepository: DownloadRepository
    @Inject lateinit var autoDownloads: AutoDownloads
    @Inject lateinit var episodeDownloads: EpisodeDownloads
    @Inject lateinit var bookDownloads: BookDownloads
    @Inject lateinit var syncQueueStarter: SyncQueueStarter
    @Inject lateinit var phoneInfoPublisher: PhoneInfoPublisher
    @Inject lateinit var wearPublisher: WearPublisher
    @Inject lateinit var wearRemotePublisher: WearRemotePublisher
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // Vor dem Wiedergabe-Service und dem Cast-Knopf; lädt im Hintergrund, ohne Play-Dienste bleibt Cast aus.
        Cast.getSingletonInstance(this).initialize()
        // Nur Beobachter und Zeitpläne: Der Prozess startet auch für Hintergrund-Jobs und Nachrichten der Uhr. Abgeglichen
        // wird beim Öffnen der App (MainActivity.onStart) bzw. von den Workern mit ihren Netz-Bedingungen.
        libraryRefresher.start()
        spokenRefresher.start()
        podcastRefresher.start()
        autoDownloads.start(applicationScope)
        episodeDownloads.start(applicationScope)
        bookDownloads.start(applicationScope)
        applicationScope.launch {
            downloadRepository.resumePending()
            syncQueueStarter.startIfPending()
        }
        applicationScope.launch { phoneInfoPublisher.publish() }
        wearPublisher.start(applicationScope)
        wearRemotePublisher.start(applicationScope)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(AlbumCoverFetcher.Keyer)
                add(AlbumCoverFetcher.Factory(artworkRepository))
                add(ArtistImageFetcher.Factory(artworkRepository))
                add(BookCoverFetcher.Factory(bookCovers))
                add(AuthorImageFetcher.Factory(authorImages))
            }
            .crossfade(true)
            .build()
}
