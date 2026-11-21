package de.kleinabersmart.earloft.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import de.kleinabersmart.earloft.core.model.Artist
import kotlinx.coroutines.launch
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/**
 * Navigation from music rows and menus to album and artist pages ("Go to album/artist" in the song menu), provided by the
 * app; `null` in previews, then the entries are missing.
 */
class LibraryLinks(
    val openAlbum: (Long) -> Unit,
    val openArtist: (Long) -> Unit,
    val artistsOfSong: suspend (songId: Long, albumId: Long?) -> List<Artist>,
)

val LocalLibraryLinks = staticCompositionLocalOf<LibraryLinks?> { null }

/** Where an artist name leads: [lookup] gives the artists with a page, [onArtistClick] opens one. */
class ArtistLink(val lookup: suspend () -> List<Artist>, val onArtistClick: (Long) -> Unit) {
    companion object {
        val None = ArtistLink(lookup = { emptyList() }, onArtistClick = {})
    }
}

/**
 * "Go to artist" from a name (player, album page, menus): [lookup] gives the artists with a page; one opens directly, several
 * are offered in a menu below [content]. [content] receives the click; without an artist page the tap does nothing.
 */
@Composable
fun ArtistPicker(
    lookup: suspend () -> List<Artist>,
    onArtistClick: (Long) -> Unit,
    content: @Composable (onClick: () -> Unit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var choices by remember { mutableStateOf<List<Artist>>(emptyList()) }
    Box {
        content {
            scope.launch {
                val artists = lookup()
                if (artists.size == 1) onArtistClick(artists.single().id) else choices = artists
            }
        }
        DropdownMenu(expanded = choices.isNotEmpty(), onDismissRequest = { choices = emptyList() }) {
            choices.forEach { artist ->
                DropdownMenuItem(
                    text = { Text(artist.name) },
                    leadingIcon = { Icon(painterResource(DesignR.drawable.ic_person), contentDescription = null) },
                    onClick = {
                        choices = emptyList()
                        onArtistClick(artist.id)
                    },
                )
            }
        }
    }
}

/** A name that leads to a page (artist, album): tappable with a small rounded ripple, [label] for TalkBack. */
fun Modifier.linkClick(label: String, onClick: () -> Unit): Modifier =
    clip(RoundedCornerShape(8.dp))
        .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
        .padding(horizontal = 6.dp, vertical = 2.dp)
