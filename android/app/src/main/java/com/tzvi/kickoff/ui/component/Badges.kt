package com.tzvi.kickoff.ui.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tzvi.kickoff.ui.theme.KickoffShapeTokens
import com.tzvi.kickoff.ui.theme.KickoffTextStyles
import com.tzvi.kickoff.ui.theme.KickoffTheme

/**
 * The LIVE marker. The dot pulses; the word does not - animating text is much harder to
 * read at a glance, and this label has to survive being seen for half a second.
 */
@Composable
fun LivePill(
    modifier: Modifier = Modifier,
    label: String = "LIVE",
    color: Color = KickoffTheme.accents.live,
) {
    val transition = rememberInfiniteTransition(label = "live-pulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot-alpha",
    )

    Row(
        modifier = modifier
            .clip(KickoffShapeTokens.pill)
            .background(color)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .size(5.dp)
                .alpha(alpha)
                .clip(KickoffShapeTokens.crest)
                .background(Color.White),
        )
        Text(
            text = label,
            style = KickoffTextStyles.badge,
            color = Color.White,
        )
    }
}

/** A neutral micro-label: the competition round, a kick-off time, a status. */
@Composable
fun MetaChip(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text = text,
        style = KickoffTextStyles.badge,
        color = content,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
