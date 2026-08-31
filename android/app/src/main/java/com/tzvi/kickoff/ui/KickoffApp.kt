package com.tzvi.kickoff.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.tzvi.kickoff.AppUiState
import com.tzvi.kickoff.ui.island.DynamicIsland
import com.tzvi.kickoff.ui.motion.Motion
import com.tzvi.kickoff.ui.navigation.KickoffNavHost
import com.tzvi.kickoff.ui.navigation.Routes
import com.tzvi.kickoff.ui.navigation.TopLevelDestination

/**
 * The app shell: navigation bar, nav graph, and the live island floating above both.
 *
 * The island deliberately sits outside the NavHost. It tracks the match, not the screen,
 * so it must survive navigation - if it lived inside a destination it would be torn down
 * and rebuilt on every transition, and the score would flicker.
 */
@Composable
fun KickoffApp(
    state: AppUiState,
    deepLink: Uri? = null,
    onDeepLinkHandled: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    var islandExpanded by rememberSaveable { mutableStateOf(false) }
    var islandDismissed by remember { mutableStateOf(false) }

    // "kickoff://match/12345" from a notification or the overlay island.
    LaunchedEffect(deepLink) {
        val uri = deepLink ?: return@LaunchedEffect
        if (uri.scheme == "kickoff" && uri.host == "match") {
            uri.lastPathSegment?.toLongOrNull()?.let { matchId ->
                navController.navigate(Routes.matchDetail(matchId))
            }
        }
        onDeepLinkHandled()
    }

    val showBottomBar = currentRoute in TopLevelDestination.entries.map { it.route }
    val startDestination =
        if (state.settings.onboardingComplete) Routes.TODAY else Routes.ONBOARDING

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                AnimatedVisibility(
                    visible = showBottomBar,
                    enter = slideInVertically(Motion.spatial()) { it } +
                        fadeIn(Motion.effects(Motion.Duration.SHORT)),
                    exit = slideOutVertically(Motion.spatial()) { it } +
                        fadeOut(Motion.effects(Motion.Duration.SHORT)),
                ) {
                    NavigationBar {
                        TopLevelDestination.entries.forEach { destination ->
                            val selected = backStackEntry?.destination?.hierarchy
                                ?.any { it.route == destination.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(destination.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) destination.selectedIcon
                                        else destination.icon,
                                        contentDescription = null,
                                    )
                                },
                                label = { Text(destination.label) },
                            )
                        }
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        ) { padding ->
            KickoffNavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        bottom = if (showBottomBar) padding.calculateBottomPadding() else 0.dp,
                    ),
            )
        }

        val islandActivity = state.liveActivity?.takeUnless { islandDismissed }
        DynamicIsland(
            activity = islandActivity,
            expanded = islandExpanded,
            onToggle = { islandExpanded = !islandExpanded },
            onOpenMatch = { matchId ->
                islandExpanded = false
                navController.navigate(Routes.matchDetail(matchId))
            },
            onDismiss = {
                islandExpanded = false
                islandDismissed = true
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(WindowInsets.statusBars.asPaddingValues())
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}
