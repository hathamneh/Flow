package io.github.aedev.flow.ui.components.account

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import io.github.aedev.flow.data.youtubeaccount.YouTubeAccount

/** An account's picture, or a person outline for Guest and accounts without one. */
@Composable
fun AccountPicture(
    account: YouTubeAccount?,
    size: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val url = account?.avatarUrl.orEmpty()
    if (url.isBlank()) {
        Icon(Icons.Outlined.AccountCircle, contentDescription = contentDescription, modifier = modifier.size(size))
    } else {
        AsyncImage(
            model = url,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(CircleShape),
        )
    }
}
