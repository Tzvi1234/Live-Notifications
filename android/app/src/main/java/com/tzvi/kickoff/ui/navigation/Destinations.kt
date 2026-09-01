package com.tzvi.kickoff.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.SportsSoccer
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.ui.graphics.vector.ImageVector

object Routes {
    const val AUTH = "auth"
    const val PROFILE = "profile"
    const val ONBOARDING = "onboarding"
    const val TODAY = "today"
    const val MATCHES = "matches"
    const val TEAMS = "teams"
    const val PREDICT = "predict"
    const val SETTINGS = "settings"

    const val MATCH_DETAIL = "match/{matchId}"
    fun matchDetail(matchId: Long) = "match/$matchId"

    const val TEAM_DETAIL = "team/{teamId}"
    fun teamDetail(teamId: Int) = "team/$teamId"

    const val ARG_MATCH_ID = "matchId"
    const val ARG_TEAM_ID = "teamId"
}

/**
 * The four top-level destinations in the navigation bar.
 *
 * Three of them answer "what is happening": today, the full fixture list, the clubs
 * behind it. Predict is the fourth because it is the one worth opening when there is no
 * match on at all.
 */
enum class TopLevelDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val icon: ImageVector,
) {
    TODAY(Routes.TODAY, "Today", Icons.Filled.Home, Icons.Outlined.Home),
    MATCHES(Routes.MATCHES, "Matches", Icons.Filled.SportsSoccer, Icons.Outlined.SportsSoccer),
    TEAMS(Routes.TEAMS, "Teams", Icons.Filled.Groups, Icons.Outlined.Groups),
    PREDICT(Routes.PREDICT, "Predict", Icons.Filled.Casino, Icons.Outlined.Casino),
}
