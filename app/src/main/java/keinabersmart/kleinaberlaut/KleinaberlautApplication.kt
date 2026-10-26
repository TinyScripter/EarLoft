package keinabersmart.kleinaberlaut

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.crossfade
import dagger.hilt.android.HiltAndroidApp
import keinabersmart.kleinaberlaut.core.data.repository.ArtworkRepository
import keinabersmart.kleinaberlaut.core.data.sync.LibraryRefresher
import keinabersmart.kleinaberlaut.ui.image.AlbumCoverFetcher
import javax.inject.Inject

@HiltAndroidApp
class KleinaberlautApplication : Application(), SingletonImageLoader.Factory {

    @Inject lateinit var libraryRefresher: LibraryRefresher
    @Inject lateinit var artworkRepository: ArtworkRepository

    override fun onCreate() {
        super.onCreate()
        libraryRefresher.start()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(AlbumCoverFetcher.Keyer)
                add(AlbumCoverFetcher.Factory(artworkRepository))
            }
            .crossfade(true)
            .build()
}
