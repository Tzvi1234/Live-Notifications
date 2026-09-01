package com.tzvi.kickoff.ui.navigation

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.tzvi.kickoff.feature.calendar.CalendarScreen
import com.tzvi.kickoff.feature.island.IslandCalibrationScreen
import com.tzvi.kickoff.feature.matchdetail.MatchDetailScreen
import com.tzvi.kickoff.feature.matches.MatchesScreen
import com.tzvi.kickoff.feature.onboarding.OnboardingScreen
import com.tzvi.kickoff.feature.settings.SettingsScreen
import com.tzvi.kickoff.feature.teams.TeamsScreen
import com.tzvi.kickoff.feature.today.TodayScreen
import com.tzvi.kickoff.ui.motion.LocalNavAnimatedScope
import com.tzvi.kickoff.ui.motion.LocalSharedTransitionScope
import com.tzvi.kickoff.ui.motion.NavTransitions

/**
 * The whole graph lives inside one [SharedTransitionLayout] so that any element on any
 * screen can hand its bounds to any element on any other. Each destination publishes its
 * own AnimatedVisibilityScope through a composition local, which is what lets a leaf
 * composable deep in a list opt into a container transform without the scopes being
 * threaded through every layer in between.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun KickoffNavHost(
    navController: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier,
) {
    SharedTransitionLayout(modifier = modifier) {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
            NavHost(
                navController = navController,
                startDestination = startDestination,
                enterTransition = NavTransitions.forwardEnter,
                exitTransition = NavTransitions.forwardExit,
                popEnterTransition = NavTransitions.backEnter,
                popExitTransition = NavTransitions.backExit,
            ) {
                composable(
                    route = Routes.ONBOARDING,
                    enterTransition = NavTransitions.onboardingEnter,
                    exitTransition = NavTransitions.onboardingExit,
                ) {
                    Scoped {
                        OnboardingScreen(
                            onFinished = {
                                navController.navigate(Routes.TODAY) {
                                    popUpTo(Routes.ONBOARDING) { inclusive = true }
                                }
                            },
                        )
                    }
                }

                composable(Routes.TODAY) {
                    Scoped {
                        TodayScreen(
                            onOpenMatch = { matchId -> navController.navigate(Routes.matchDetail(matchId)) },
                            onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                            onOpenTeams = { navController.navigate(Routes.TEAMS) },
                        )
                    }
                }

                composable(Routes.MATCHES) {
                    Scoped {
                        MatchesScreen(
                            onOpenMatch = { matchId -> navController.navigate(Routes.matchDetail(matchId)) },
                        )
                    }
                }

                composable(Routes.TEAMS) {
                    Scoped {
                        TeamsScreen(
                            onOpenMatch = { matchId -> navController.navigate(Routes.matchDetail(matchId)) },
                        )
                    }
                }

                composable(Routes.CALENDAR) {
                    Scoped { CalendarScreen() }
                }

                // Opened from a settings row, so it grows out of that row rather than
                // sliding in from the edge.
                composable(
                    route = Routes.SETTINGS,
                    enterTransition = NavTransitions.expandEnter,
                    exitTransition = NavTransitions.expandExit,
                    popEnterTransition = NavTransitions.backEnter,
                    popExitTransition = NavTransitions.expandExit,
                ) {
                    Scoped {
                        SettingsScreen(
                            onBack = { navController.popBackStack() },
                            onCalibrateCutout = {
                                navController.navigate(Routes.ISLAND_CALIBRATION)
                            },
                        )
                    }
                }

                // Drawn against near-black up into the cutout row, so it slides rather
                // than growing out of the settings row it was opened from.
                composable(Routes.ISLAND_CALIBRATION) {
                    Scoped {
                        IslandCalibrationScreen(onBack = { navController.popBackStack() })
                    }
                }

                composable(
                    route = Routes.MATCH_DETAIL,
                    arguments = listOf(
                        navArgument(Routes.ARG_MATCH_ID) { type = NavType.LongType },
                    ),
                    enterTransition = NavTransitions.expandEnter,
                    exitTransition = NavTransitions.expandExit,
                    popEnterTransition = NavTransitions.backEnter,
                    popExitTransition = NavTransitions.expandExit,
                ) {
                    Scoped { MatchDetailScreen(onBack = { navController.popBackStack() }) }
                }
            }
        }
    }
}

/**
 * Publishes this destination's AnimatedVisibilityScope, which is the other half of the
 * pair a shared-bounds transform needs.
 */
@Composable
private fun androidx.compose.animation.AnimatedContentScope.Scoped(
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalNavAnimatedScope provides this) { content() }
}
