package com.tzvi.kickoff.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Corner radii sit a little above the Material defaults: the app is a stack of cards
 * on a dense screen, and the softer containers keep the scoreboards reading as
 * separate objects rather than as one striped list.
 */
val KickoffShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

object KickoffShapeTokens {
    val crest = RoundedCornerShape(percent = 50)
    val pill = RoundedCornerShape(percent = 50)
    val scoreboard = RoundedCornerShape(24.dp)
    val chip = RoundedCornerShape(10.dp)
}
