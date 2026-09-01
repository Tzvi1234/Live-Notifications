package com.tzvi.kickoff.feature.auth

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.tzvi.kickoff.ui.component.EmptyState
import com.tzvi.kickoff.ui.theme.KickoffTheme

/**
 * What an account-only feature draws instead of its content when nobody is signed in.
 *
 * It is the app's ordinary empty state, not a wall: the prediction game is one part of
 * matchUP, and finding it locked should read like a list with nothing in it yet rather
 * than like a door. Screens that use this stay reachable, keep their navigation, and
 * hand the user one button that goes to the auth screen.
 */
@Composable
fun AccountRequired(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = "Sign in",
    onAction: (() -> Unit)? = null,
) {
    EmptyState(
        title = title,
        body = body,
        icon = Icons.Outlined.AccountCircle,
        actionLabel = actionLabel,
        onAction = onAction,
        modifier = modifier,
    )
}

@Preview(name = "Account required")
@Composable
private fun AccountRequiredPreview() {
    KickoffTheme {
        AccountRequired(
            title = "Predictions need an account",
            body = "Your calls are kept against your account so they survive a reinstall.",
            onAction = {},
        )
    }
}
