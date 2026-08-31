package com.tzvi.kickoff.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.tzvi.kickoff.core.model.Team
import com.tzvi.kickoff.ui.motion.sharedElementTransform
import com.tzvi.kickoff.ui.theme.KickoffShapeTokens

/**
 * A club crest, with a monogram disc standing in while it loads or if it never does.
 *
 * Crest URLs come from the provider's CDN and fail more often than you would like -
 * a hole in a scoreboard is far more noticeable than a slightly plain circle.
 */
@Composable
fun TeamCrest(
    team: Team,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    shared: Boolean = false,
) {
    CrestImage(
        url = team.crestUrl,
        fallback = team.code,
        modifier = if (shared) {
            modifier.sharedElementTransform(
                com.tzvi.kickoff.ui.motion.TransformKeys.teamCrest(team.id),
            )
        } else {
            modifier
        },
        size = size,
    )
}

@Composable
fun CrestImage(
    url: String?,
    fallback: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    var failed by remember(url) { mutableStateOf(url == null) }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        if (failed) {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(KickoffShapeTokens.crest)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = fallback.take(3).uppercase(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    fontSize = (size.value * 0.3f).sp,
                    maxLines = 1,
                )
            }
        } else {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(url)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size),
                onError = { failed = true },
            )
        }
    }
}
