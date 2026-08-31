package com.tzvi.kickoff.ui.island

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tzvi.kickoff.core.model.LiveActivity
import com.tzvi.kickoff.ui.component.CrestImage
import com.tzvi.kickoff.ui.component.LivePill
import com.tzvi.kickoff.ui.motion.Motion
import com.tzvi.kickoff.ui.theme.KickoffTextStyles
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A live-score island, in the spirit of the iPhone's.
 *
 * It sits under the status bar as a dark pill carrying just the crests and the
 * scoreline, and opens into a full match card when tapped. The morph is one continuous
 * gesture: the container's size and corner radius spring to their new values while the
 * contents cross-fade, so the pill visibly *becomes* the card rather than being replaced
 * by it.
 *
 * The same composable is used inside the app and inside the optional system overlay
 * ([IslandOverlayService]), which is why it takes plain state and callbacks and owns no
 * view model of its own.
 */
@Composable
fun DynamicIsland(
    activity: LiveActivity.MatchActivity?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenMatch: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    // AnimatedVisibility keeps composing its content through the exit animation, by which
    // time `activity` is already null. Holding the last non-null value lets the island
    // animate away still showing the score instead of collapsing into an empty pill.
    var retained by remember { mutableStateOf(activity) }
    if (activity != null && activity != retained) retained = activity

    AnimatedVisibility(
        visible = activity != null,
        enter = slideInVertically(
            animationSpec = spring(dampingRatio = 0.62f, stiffness = 320f),
            initialOffsetY = { -it },
        ) + fadeIn(tween(Motion.Duration.MEDIUM)),
        exit = slideOutVertically(
            animationSpec = tween(Motion.Duration.MEDIUM),
            targetOffsetY = { -it },
        ) + fadeOut(tween(Motion.Duration.SHORT)),
        modifier = modifier,
    ) {
        val shown = retained ?: return@AnimatedVisibility

        val corner by animateDpAsState(
            targetValue = if (expanded) 30.dp else 24.dp,
            animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
            label = "island-corner",
        )
        val horizontalPadding by animateDpAsState(
            targetValue = if (expanded) 16.dp else 14.dp,
            animationSpec = Motion.dpSpring(),
            label = "island-hpad",
        )

        Box(
            modifier = Modifier
                .then(if (expanded) Modifier.fillMaxWidth() else Modifier.widthIn(min = 168.dp))
                .shadow(
                    elevation = if (expanded) 18.dp else 10.dp,
                    shape = RoundedCornerShape(corner),
                    clip = false,
                )
                .clip(RoundedCornerShape(corner))
                .background(IslandInk)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggle,
                )
                .padding(horizontal = horizontalPadding, vertical = if (expanded) 14.dp else 8.dp),
        ) {
            AnimatedContent(
                targetState = expanded,
                transitionSpec = {
                    // The container is already springing to its new size; the contents
                    // only need to hand over, so they fade and scale a little instead of
                    // travelling, which would fight the container's movement.
                    (fadeIn(tween(Motion.Duration.MEDIUM, delayMillis = 60)) +
                        scaleIn(initialScale = 0.94f, animationSpec = tween(Motion.Duration.MEDIUM)))
                        .togetherWith(
                            fadeOut(tween(Motion.Duration.SHORT)) +
                                scaleOut(targetScale = 0.96f, animationSpec = tween(Motion.Duration.SHORT)),
                        )
                },
                label = "island-content",
            ) { isExpanded ->
                if (isExpanded) {
                    IslandExpanded(
                        activity = shown,
                        onOpenMatch = { onOpenMatch(shown.match.id) },
                        onDismiss = onDismiss,
                    )
                } else {
                    IslandCompact(activity = shown)
                }
            }
        }
    }
}

@Composable
private fun IslandCompact(activity: LiveActivity.MatchActivity) {
    val match = activity.match
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CrestImage(match.home.crestUrl, match.home.code, size = 22.dp)
        Text(
            text = scoreLabel(activity),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
        )
        CrestImage(match.away.crestUrl, match.away.code, size = 22.dp)
        Spacer(Modifier.width(2.dp))
        Text(
            text = clockLabel(activity),
            color = IslandMuted,
            style = KickoffTextStyles.clock,
        )
    }
}

@Composable
private fun IslandExpanded(
    activity: LiveActivity.MatchActivity,
    onOpenMatch: () -> Unit,
    onDismiss: (() -> Unit)?,
) {
    val match = activity.match
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = match.leagueName,
                color = IslandMuted,
                style = KickoffTextStyles.badge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (match.isLive) LivePill()
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IslandTeam(
                crestUrl = match.home.crestUrl,
                code = match.home.code,
                name = match.home.name,
                modifier = Modifier.weight(1f),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = scoreLabel(activity),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 30.sp,
                )
                Text(
                    text = clockLabel(activity),
                    color = IslandMuted,
                    style = KickoffTextStyles.clock,
                )
            }
            IslandTeam(
                crestUrl = match.away.crestUrl,
                code = match.away.code,
                name = match.away.name,
                modifier = Modifier.weight(1f),
                trailing = true,
            )
        }

        activity.latestEvent?.let { event ->
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(IslandStrip)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = event.minuteLabel,
                    color = IslandAccent,
                    style = KickoffTextStyles.clock,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = event.headline(),
                    color = Color.White,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IslandButton(
                label = "Open match",
                onClick = onOpenMatch,
                modifier = Modifier.weight(1f),
                filled = true,
            )
            if (onDismiss != null) {
                IslandButton(label = "Hide", onClick = onDismiss, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun IslandTeam(
    crestUrl: String?,
    code: String,
    name: String,
    modifier: Modifier = Modifier,
    trailing: Boolean = false,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = if (trailing) Alignment.End else Alignment.Start,
    ) {
        CrestImage(crestUrl, code, size = 34.dp)
        Spacer(Modifier.height(4.dp))
        Text(
            text = name,
            color = Color.White,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun IslandButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
) {
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (filled) IslandAccent else IslandStrip)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (filled) IslandInk else Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun scoreLabel(activity: LiveActivity.MatchActivity): String =
    activity.match.score?.let { "${it.home} – ${it.away}" } ?: "vs"

private fun clockLabel(activity: LiveActivity.MatchActivity): String {
    val match = activity.match
    return when (activity.stage) {
        LiveActivity.MatchActivity.Stage.PRE_MATCH -> {
            val minutes = Duration.between(Instant.now(), match.kickoffAt).toMinutes()
            if (minutes in 0..99) "${minutes}m"
            else KICKOFF_TIME.format(match.kickoffAt.atZone(ZoneId.systemDefault()))
        }
        LiveActivity.MatchActivity.Stage.LIVE -> match.clockLabel
        LiveActivity.MatchActivity.Stage.FULL_TIME -> "FT"
    }
}

private val KICKOFF_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

// The island is always dark, in both themes: it imitates the hardware cutout it sits
// next to, and a light pill floating over a light app has no edge to read against.
internal val IslandInk = Color(0xFF0B140C)
internal val IslandMuted = Color(0xFF9BB0A0)
internal val IslandStrip = Color(0x1FFFFFFF)
internal val IslandAccent = Color(0xFF3FE56C)
