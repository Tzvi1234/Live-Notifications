package com.tzvi.kickoff.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tzvi.kickoff.ui.theme.KickoffShapeTokens
import java.util.Locale

/**
 * A person's profile picture, with their initials standing in until it loads.
 *
 * The same disc wherever somebody appears - the greeting on the home screen, the top of
 * Settings, a message in the group chat, a row on the leaderboard - because a face that
 * changes shape between screens reads as two different people. It lived inside the
 * prediction feature until three other screens needed it.
 *
 * A blank name is not a bug: an account can exist before anybody has typed a name into it,
 * and the disc is drawn plain rather than with a stray character in it.
 */
@Composable
fun Avatar(
    name: String,
    url: String?,
    modifier: Modifier = Modifier,
    size: Dp = AvatarDefaults.small,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(KickoffShapeTokens.crest)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            CrestImage(url = url, fallback = name, size = size)
        } else {
            val initials = initialsOf(name)
            if (initials.isNotEmpty()) {
                Text(
                    text = initials,
                    style = if (size >= AvatarDefaults.large) {
                        MaterialTheme.typography.headlineSmall
                    } else {
                        MaterialTheme.typography.labelMedium
                    },
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

object AvatarDefaults {
    /** Beside a line of text: a chat message, a leaderboard row. */
    val small = 32.dp

    /** In a header that names somebody, next to a greeting. */
    val medium = 44.dp

    /** The subject of the screen, as on the profile editor and the top of Settings. */
    val large = 88.dp
}

/**
 * One letter from a single name, two from a full one.
 *
 * `Locale.ROOT`, not the device's: this is one letter of somebody's name, and a Turkish
 * device would otherwise turn an initial "i" into "İ".
 */
internal fun initialsOf(name: String): String {
    val parts = name.trim().split(' ', '\t').filter { it.isNotBlank() }
    return when (parts.size) {
        0 -> ""
        1 -> parts[0].take(1).uppercase(Locale.ROOT)
        else -> (parts.first().take(1) + parts.last().take(1)).uppercase(Locale.ROOT)
    }
}
