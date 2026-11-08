package de.kleinabersmart.earloft.core.data.wear

import de.kleinabersmart.earloft.core.model.ReplayGain
import de.kleinabersmart.earloft.core.model.SourceType
import de.kleinabersmart.earloft.core.model.WearSelectionKind

/**
 * Was auf die Uhr soll, aufgelöst am Handy (Wear-Konzept 4.1/5): die Auswahlen mit ihren Songs in
 * Reihenfolge und jeder Song einmal mit allem, was die Uhr zum Laden braucht. Die App bildet daraus
 * das Manifest für den Data Layer.
 */
data class WearContent(
    val selections: List<WearSelectionContent> = emptyList(),
    val songs: List<WearSongContent> = emptyList(),
)

data class WearSelectionContent(
    val key: String,
    val kind: WearSelectionKind,
    val refId: Long?,
    /** Album- bzw. Playlist-Name; leer bei Favoriten (den Namen setzt die Oberfläche). */
    val title: String,
    /** Reihenfolge der Auswahl; Duplikate bei Playlists erlaubt. */
    val songIds: List<Long>,
)

data class WearSongContent(
    val songId: Long,
    /** Bevorzugte `sourceUid` am Handy (lokal vor Server, dann wie beim Abspielen). */
    val key: String,
    val title: String,
    val artists: List<String>,
    val artistDisplay: String,
    val album: String,
    val albumArtist: String,
    val isCompilation: Boolean,
    val discNumber: Int,
    val trackNumber: Int,
    val durationMs: Long,
    val year: Int?,
    val genre: String?,
    val mbid: String?,
    val albumMbid: String?,
    val replayGain: ReplayGain?,
    /** Server-Quellen in der Reihenfolge, in der das Handy sie bevorzugt. */
    val serverSources: List<WearServerSource>,
    /** `null` = das Handy hat keine Datei (weder lokal noch heruntergeladen). */
    val phoneFile: WearPhoneFile?,
)

data class WearServerSource(
    val uid: String,
    val sourceId: String,
    val remoteId: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val bitRate: Int?,
    val coverRemoteId: String?,
)

/** Eine Datei auf dem Handy (lokal oder fertiger Download), die es der Uhr schicken kann. */
data class WearPhoneFile(
    val uid: String,
    val sizeBytes: Long?,
    val mimeType: String?,
)

/** Ein Server, wie die Uhr ihn spiegelt – ohne Passwort (Wear-Konzept 6). */
data class MirroredServer(
    val sourceId: String,
    /** Immer ein Server-Typ ([SourceType.isServer]). */
    val type: SourceType,
    val name: String,
    val baseUrl: String,
    val username: String,
    val priority: Int,
    val formPost: Boolean,
    /**
     * Ändert sich mit den Zugangsdaten am Handy (neues Passwort, neu angemeldet), verrät aber nichts über sie; `null`, wenn
     * das Handy keine hat. Die Uhr fragt dann neu an, statt erst an einer Ablehnung des Servers zu merken, dass ihre veraltet sind.
     */
    val credentialsStamp: String? = null,
)
