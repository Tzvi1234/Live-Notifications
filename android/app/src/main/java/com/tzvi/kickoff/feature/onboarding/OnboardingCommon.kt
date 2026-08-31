package com.tzvi.kickoff.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tzvi.kickoff.core.model.League
import com.tzvi.kickoff.core.model.Team

/** The handful of measurements the flow reuses, kept in one place rather than inline. */
internal object OnboardingSpacing {
    val screen = 24.dp
    val block = 18.dp
    val card = 18.dp
    val tight = 8.dp
}

/** The title-and-explanation block every page after the welcome opens with. */
@Composable
internal fun PageHeading(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(OnboardingSpacing.tight),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** An icon plus a line of copy, used for the welcome page's three promises. */
@Composable
internal fun FeatureLine(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Sample content for the @Preview functions in this package. */
internal object OnboardingSamples {
    val leagues = listOf(
        League(39, "Premier League", "England", null, 2026),
        League(140, "La Liga", "Spain", null, 2026),
        League(135, "Serie A", "Italy", null, 2026),
        League(78, "Bundesliga", "Germany", null, 2026),
    )

    val teams = listOf(
        TeamOption(Team(42, "Arsenal", "Arsenal", null, "England"), leagues[0]),
        TeamOption(Team(49, "Chelsea", "Chelsea", null, "England"), leagues[0]),
        TeamOption(Team(529, "Barcelona", "Barcelona", null, "Spain"), leagues[1]),
        TeamOption(Team(541, "Real Madrid", "Real Madrid", null, "Spain"), leagues[1]),
    )
}
