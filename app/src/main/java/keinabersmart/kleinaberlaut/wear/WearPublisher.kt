package keinabersmart.kleinaberlaut.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataClient
import dagger.hilt.android.qualifiers.ApplicationContext
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.data.wear.WearContent
import keinabersmart.kleinaberlaut.core.data.wear.WearSelectionRepository
import keinabersmart.kleinaberlaut.core.data.wear.WearServerMirror
import keinabersmart.kleinaberlaut.core.playback.ReplayGainSettings
import keinabersmart.kleinaberlaut.core.wearsync.WearCatalogDto
import keinabersmart.kleinaberlaut.core.wearsync.WearCatalogPlaylistDto
import keinabersmart.kleinaberlaut.core.wearsync.WearCodec
import keinabersmart.kleinaberlaut.core.wearsync.WearPaths
import keinabersmart.kleinaberlaut.core.wearsync.WearServerDto
import keinabersmart.kleinaberlaut.core.wearsync.WearServersDto
import keinabersmart.kleinaberlaut.core.wearsync.WearSettingsDto
import keinabersmart.kleinaberlaut.core.wearsync.hashesAt
import keinabersmart.kleinaberlaut.core.wearsync.putAssetPayload
import keinabersmart.kleinaberlaut.core.wearsync.putPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hält die DataItems für die Uhr aktuell (Wear-Konzept 4): Manifest, Server und Einstellungen. Läuft,
 * solange der App-Prozess lebt – nur dann kann sich etwas ändern. Ohne Uhr bzw. Data Layer liegen die
 * DataItems nur lokal bei den Play-Diensten.
 */
@Singleton
class WearPublisher @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dataClient: DataClient,
    private val selections: WearSelectionRepository,
    private val servers: WearServerMirror,
    private val watchSettings: WatchSettingsRepository,
    private val replayGainSettings: ReplayGainSettings,
) {
    /** Zuletzt geschriebenes Manifest; `null` = noch beim Data Layer nachsehen. */
    private var publishedHash: String? = null

    fun start(scope: CoroutineScope) {
        scope.launch { selections.content().collect { publishManifest(it) } }
        scope.launch {
            servers.servers()
                .map { list -> WearServersDto(list.map { WearServerDto(it.sourceId, it.name, it.baseUrl, it.username, it.priority, it.formPost) }) }
                .distinctUntilChanged()
                .collect { dataClient.putPayload(WearPaths.SERVERS, WearCodec.encode(it)) }
        }
        scope.launch {
            selections.catalog()
                .map { catalog ->
                    WearCatalogDto(
                        favoritesCount = catalog.favoritesCount,
                        favoritesSelected = catalog.favoritesSelected,
                        playlists = catalog.playlists.map {
                            WearCatalogPlaylistDto(it.playlistId, it.name, it.songCount, it.serverName, it.selected)
                        },
                    )
                }
                .distinctUntilChanged()
                .collect { dataClient.putPayload(WearPaths.CATALOG, WearCodec.encode(it)) }
        }
        scope.launch {
            combine(watchSettings.settings, replayGainSettings.mode, replayGainSettings.preAmpDb) { watch, mode, preAmp ->
                WearSettingsDto(
                    quality = watch.quality,
                    storageLimitBytes = watch.storageLimitGb?.let { it * BYTES_PER_GB },
                    requireCharging = watch.requireCharging,
                    replayGainMode = mode,
                    preAmpDb = preAmp,
                )
            }
                .distinctUntilChanged()
                .collect { dataClient.putPayload(WearPaths.SETTINGS, WearCodec.encode(it)) }
        }
    }

    /** Nur bei geändertem Inhalt – sonst löste jeder Abgleich am Handy Arbeit auf der Uhr aus. */
    private suspend fun publishManifest(content: WearContent) {
        val manifest = WearManifestBuilder.build(content, context.getString(R.string.favorites_title))
        val hash = WearCodec.manifestHash(manifest)
        if (publishedHash == null) publishedHash = dataClient.hashesAt(WearPaths.MANIFEST).values.firstOrNull()
        if (hash == publishedHash) return
        if (dataClient.putAssetPayload(WearPaths.MANIFEST, WearCodec.encodeManifest(manifest), hash)) {
            publishedHash = hash
            Log.d(TAG, "Manifest: ${manifest.selections.size} Auswahlen, ${manifest.songs.size} Songs")
        }
    }

    private companion object {
        const val TAG = "WearPublisher"
        const val BYTES_PER_GB = 1_000_000_000L
    }
}
