package de.kleinabersmart.earloft

import android.app.Application
import androidx.annotation.OptIn
import androidx.media3.cast.Cast
import androidx.media3.common.util.UnstableApi
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.crossfade
import dagger.hilt.android.HiltAndroidApp
import de.kleinabersmart.earloft.core.data.backup.UserDataBackup
import de.kleinabersmart.earloft.core.data.repository.ArtworkRepository
import de.kleinabersmart.earloft.core.data.repository.OfflineImages
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import de.kleinabersmart.earloft.core.data.spoken.AuthorImages
import de.kleinabersmart.earloft.core.data.spoken.BookCovers
import de.kleinabersmart.earloft.core.data.podcast.FeedCredentials
import de.kleinabersmart.earloft.core.data.podcast.PodcastRefresher
import de.kleinabersmart.earloft.core.data.spoken.SpokenRefresher
import de.kleinabersmart.earloft.core.data.sync.LibraryRefresher
import de.kleinabersmart.earloft.core.data.sync.SyncQueueStarter
import de.kleinabersmart.earloft.core.data.podcast.PodcastSettings
import de.kleinabersmart.earloft.core.download.AutoDownloads
import de.kleinabersmart.earloft.core.download.BookDownloads
import de.kleinabersmart.earloft.core.download.DownloadRepository
import de.kleinabersmart.earloft.core.download.EpisodeDownloads
import de.kleinabersmart.earloft.ui.image.AlbumCoverFetcher
import de.kleinabersmart.earloft.ui.image.ArtistImageFetcher
import de.kleinabersmart.earloft.ui.image.AuthorImageFetcher
import de.kleinabersmart.earloft.ui.image.BookCoverFetcher
import de.kleinabersmart.earloft.ui.image.StoredPodcastImageFetcher
import de.kleinabersmart.earloft.wear.PhoneInfoPublisher
import de.kleinabersmart.earloft.core.playback.QueuePersistence
import de.kleinabersmart.earloft.core.playback.TrialPodcastCleaner
import de.kleinabersmart.earloft.wear.WearPublisher
import de.kleinabersmart.earloft.wear.WearRemotePublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class EarLoftApplication : Application(), SingletonImageLoader.Factory {

    @Inject lateinit var userDataBackup: UserDataBackup
    @Inject lateinit var libraryRefresher: LibraryRefresher
    @Inject lateinit var spokenRefresher: SpokenRefresher
    @Inject lateinit var podcastRefresher: PodcastRefresher
    @Inject lateinit var feedCredentials: FeedCredentials
    @Inject lateinit var bookCovers: BookCovers
    @Inject lateinit var authorImages: AuthorImages
    @Inject lateinit var artworkRepository: ArtworkRepository
    @Inject lateinit var offlineImages: OfflineImages
    @Inject lateinit var downloadRepository: DownloadRepository
    @Inject lateinit var autoDownloads: AutoDownloads
    @Inject lateinit var episodeDownloads: EpisodeDownloads
    @Inject lateinit var bookDownloads: BookDownloads
    @Inject lateinit var trialPodcastCleaner: TrialPodcastCleaner
    @Inject lateinit var queuePersistence: QueuePersistence
    @Inject lateinit var podcastSettings: PodcastSettings
    @Inject lateinit var syncQueueStarter: SyncQueueStarter
    @Inject lateinit var phoneInfoPublisher: PhoneInfoPublisher
    @Inject lateinit var wearPublisher: WearPublisher
    @Inject lateinit var wearRemotePublisher: WearRemotePublisher
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // Before the playback service and the cast button; loads in the background, without Play services Cast stays off.
        Cast.getSingletonInstance(this).initialize()
        // After a device change, first restore the backed-up user data, then mirror changes to user.db.
        userDataBackup.start()
        // Before anything that streams or downloads: credentials of protected feeds for requests outside the feed fetch.
        feedCredentials.start()
        // Only observers and schedules: the process also starts for background jobs and watch messages. Syncing
        // happens when the app is opened (MainActivity.onStart) or by the workers with their network constraints.
        libraryRefresher.start()
        spokenRefresher.start()
        podcastRefresher.start()
        autoDownloads.start(applicationScope)
        episodeDownloads.start(applicationScope, queuePersistence.lastBookIds)
        bookDownloads.start(applicationScope, queuePersistence.lastBookIds)
        trialPodcastCleaner.start(applicationScope)
        offlineImages.start()
        applicationScope.launch { podcastSettings.rememberAppleCountry() }
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
                // Before Coil's network fetcher: subscription images already on the device, offline too.
                add(StoredPodcastImageFetcher.Factory(bookCovers))
            }
            .crossfade(true)
            .build()
}
