package keinabersmart.kleinaberlaut.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import keinabersmart.kleinaberlaut.core.designsystem.R
import keinabersmart.kleinaberlaut.core.model.AppUris

/** Stärke der feinen Kontur um Cover und Bilder. */
val CoverOutlineWidth = 1.dp

/** Farbe der feinen Kontur um Cover und Bilder: Helle Bilder heben sich so vom Untergrund ab, dunkle bleiben unverändert. */
@Composable
fun coverOutlineColor(): Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)

/** Quadratisches Album-Cover; ohne Cover bleibt das Noten-Symbol sichtbar. */
@Composable
fun AlbumArt(
    albumId: Long?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(CoverOutlineWidth, coverOutlineColor(), shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_music_note),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(0.4f),
        )
        if (albumId != null) {
            AsyncImage(
                model = AppUris.albumCover(albumId),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
