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
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.tzvi.kickoff.ui.motion.Motion
import com.tzvi.kickoff.ui.navigation.KickoffNavHost
import com.tzvi.kickoff.data.predict.PendingInvite
import com.tzvi.kickoff.ui.navigation.Routes
import com.tzvi.kickoff.ui.navigation.TopLevelDestination

/**
 * The app shell: navigation bar and nav graph.
 *
 * The live island is deliberately NOT here. It is a system overlay that exists to put the
 * score in front of you while you are somewhere else, so drawing it over matchUP's own
 * screens only ever put a second copy of the scoreline on top of the toolbar. See
 * IslandOverlayService, which hides itself whenever this app is in the foreground.
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


    LaunchedEffect(deepLink) {
        val uri = deepLink ?: return@LaunchedEffect
        // "kickoff://match/12345" from a notification or the overlay island.
        if (uri.scheme == "kickoff" && uri.host == "match") {
            uri.lastPathSegment?.toLongOrNull()?.let { matchId ->
                navController.navigate(Routes.matchDetail(matchId))
            }
        }
        // "matchup://join/3YJK2CYK" from a friend. The code itself was parked by the
        // activity before this ran, because joining needs an account and this may well be
        // the tap that installed the app; all that is left here is to go and redeem it.
        if (PendingInvite.codeIn(uri.scheme, uri.host, uri.lastPathSegment) != null) {
            navController.navigate(Routes.PREDICT)
        }
        onDeepLinkHandled()
    }

    val showBottomBar = currentRoute in TopLevelDestination.entries.map { it.route }

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
                startDestination = state.startDestination,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        bottom = if (showBottomBar) padding.calculateBottomPadding() else 0.dp,
                    ),
            )
        }

        // No island inside matchUP. It exists to put the score somewhere you are NOT
        // looking - over the launcher, over another app - and over its own match screens
        // it was just a second copy of the same numbers sitting on the toolbar.
        // IslandOverlayService draws it, and hides itself while this app is in front.
    }
}
