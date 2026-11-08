package de.kleinabersmart.earloft.core.database.entity

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions

// Volltextindizes über die Bibliothekstabellen. Room hält sie per Trigger synchron;
// unicode61 ignoriert Groß-/Kleinschreibung und gängige Diakritika ("bjork" findet "Björk").

@Fts4(contentEntity = SongEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "songs_fts")
data class SongFtsEntity(
    val title: String,
    val artistDisplay: String,
)

@Fts4(contentEntity = AlbumEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "albums_fts")
data class AlbumFtsEntity(
    val title: String,
    val albumArtistDisplay: String,
)

@Fts4(contentEntity = ArtistEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "artists_fts")
data class ArtistFtsEntity(
    val name: String,
)
