package keinabersmart.kleinaberlaut.core.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import keinabersmart.kleinaberlaut.core.data.repository.PlaybackSourceResolver
import keinabersmart.kleinaberlaut.core.datasource.PlaybackLocation
import keinabersmart.kleinaberlaut.core.model.AppUris
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * Löst `kal://song/<id>` beim Öffnen in die aktuell beste Quelle auf (Konzept 7.2).
 * Läuft auf dem Lade-Thread von ExoPlayer, daher ist Blockieren hier erlaubt.
 */
@OptIn(UnstableApi::class)
internal class SongDataSourceResolver(
    private val sourceResolver: PlaybackSourceResolver,
) : ResolvingDataSource.Resolver {

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val songId = AppUris.parseSongId(dataSpec.uri.toString()) ?: return dataSpec
        val location = runBlocking { sourceResolver.resolve(songId) }
            ?: throw IOException("Keine abspielbare Quelle für Song $songId")
        return when (location) {
            is PlaybackLocation.LocalFile -> dataSpec.withUri(location.uri)
            is PlaybackLocation.Remote -> dataSpec.buildUpon().setUri(location.uri).setKey(location.cacheKey).build()
        }
    }
}
