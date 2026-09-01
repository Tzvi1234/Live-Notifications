package com.tzvi.kickoff.feature.auth

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.tzvi.kickoff.feature.onboarding.OnboardingSpacing
import com.tzvi.kickoff.ui.component.KickoffLoader
import com.tzvi.kickoff.ui.theme.KickoffShapeTokens
import com.tzvi.kickoff.ui.theme.KickoffTheme

/**
 * The two things a matchUP account has of its own: a name to put on a prediction, and a
 * face to put beside it.
 *
 * Everything else about the account lives at Clerk and is edited there. Duplicating a
 * password form here would mean a second place for it to be wrong.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    /** Where signing out goes, and where a signed-out visitor is offered a way in. */
    onOpenAuth: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) viewModel.setProfileImage(uri) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Your profile") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = OnboardingSpacing.screen),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(OnboardingSpacing.block),
        ) {
            if (!state.signedIn) {
                AccountRequired(
                    title = "Not signed in",
                    body = if (state.accountsAvailable) {
                        "Sign in and your name, picture and predictions follow you " +
                            "between devices."
                    } else {
                        "Accounts are not configured for this build."
                    },
                    actionLabel = if (state.accountsAvailable) "Sign in" else null,
                    onAction = if (state.accountsAvailable) onOpenAuth else null,
                )
                return@Column
            }

            Spacer(Modifier.height(OnboardingSpacing.tight))

            ProfilePicture(
                imageUrl = state.imageUrl,
                busy = state.savingImage,
                onPick = {
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
            )

            Text(
                text = state.email,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            OutlinedTextField(
                value = state.firstName,
                onValueChange = viewModel::onFirstNameChange,
                label = { Text("First name") },
                singleLine = true,
                enabled = !state.savingName,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.lastName,
                onValueChange = viewModel::onLastNameChange,
                label = { Text("Last name") },
                singleLine = true,
                enabled = !state.savingName,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { viewModel.saveName() }),
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = viewModel::saveName,
                enabled = state.canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                if (state.savingName) {
                    KickoffLoader(size = 18.dp, ringColor = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("Save name")
                }
            }

            AuthMessage(error = state.error, notice = state.notice)

            OutlinedButton(
                onClick = { viewModel.signOut(onOpenAuth) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text("Sign out")
            }

            Spacer(Modifier.height(OnboardingSpacing.block))
        }
    }
}

/**
 * The avatar, and the only control that changes it.
 *
 * Tapping the picture is the whole gesture: a separate "change photo" button beside a
 * picture that already looks tappable is one control too many.
 */
@Composable
private fun ProfilePicture(
    imageUrl: String?,
    busy: Boolean,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(AVATAR_SIZE)
            .clip(KickoffShapeTokens.crest)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = !busy, onClick = onPick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            busy -> KickoffLoader(size = 40.dp)

            imageUrl != null -> AsyncImage(
                model = imageUrl,
                contentDescription = "Your profile picture",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(AVATAR_SIZE),
            )

            else -> Icon(
                imageVector = Icons.Outlined.AccountCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(56.dp),
            )
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Outlined.PhotoCamera,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Tap the circle to choose a picture",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val AVATAR_SIZE = 112.dp

@Preview(name = "Profile")
@Composable
private fun ProfilePicturePreview() {
    KickoffTheme {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ProfilePicture(imageUrl = null, busy = false, onPick = {})
        }
    }
}
