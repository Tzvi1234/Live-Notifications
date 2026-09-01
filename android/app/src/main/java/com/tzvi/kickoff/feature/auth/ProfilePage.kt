package com.tzvi.kickoff.feature.auth

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.capitalize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tzvi.kickoff.feature.onboarding.OnboardingSpacing
import com.tzvi.kickoff.ui.component.Avatar
import com.tzvi.kickoff.ui.component.AvatarDefaults
import com.tzvi.kickoff.ui.component.KickoffLoader
import com.tzvi.kickoff.ui.theme.KickoffShapeTokens
import com.tzvi.kickoff.ui.theme.KickoffShapes

/**
 * The last step of a sign-up: who you are, to the rest of your group.
 *
 * Placed here rather than left to Settings because the name and the picture are not the
 * account's decoration - they are how you appear on a leaderboard and beside a message,
 * and a group full of people called "Anonymous" is a group nobody can follow. Asked once,
 * at the only moment the user is already thinking about their account.
 *
 * Entirely skippable, and it says so. Nothing downstream breaks without it: the avatar
 * falls back to initials and the leaderboard to the email's local part.
 */
@Composable
internal fun ProfilePage(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) viewModel.setProfileImage(uri) }

    val displayName = listOf(state.firstName, state.lastName)
        .filter { it.isNotBlank() }
        .joinToString(" ")

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = OnboardingSpacing.screen),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))

        Box(contentAlignment = Alignment.BottomEnd) {
            Avatar(
                name = displayName.ifBlank { state.email },
                url = state.imageUrl,
                size = AvatarDefaults.large,
                modifier = Modifier.clickable(enabled = !state.savingImage) {
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
            )
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(KickoffShapeTokens.crest)
                    .clickable(enabled = !state.savingImage) {
                        imagePicker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (state.savingImage) {
                    KickoffLoader(size = 20.dp)
                } else {
                    Icon(
                        imageVector = Icons.Outlined.AddAPhoto,
                        contentDescription = "Choose a profile picture",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = "Say who you are",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "This is how you appear on a leaderboard and beside your messages. " +
                "You can change it whenever you like.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(28.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = state.firstName,
                onValueChange = viewModel::onFirstNameChange,
                label = { Text("First name") },
                singleLine = true,
                enabled = !state.savingName,
                shape = KickoffShapes.medium,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Next,
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.lastName,
                onValueChange = viewModel::onLastNameChange,
                label = { Text("Last name") },
                singleLine = true,
                enabled = !state.savingName,
                shape = KickoffShapes.medium,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Done,
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        AuthMessage(error = state.error, notice = state.notice)

        Spacer(Modifier.height(28.dp))
        Button(
            onClick = {
                // Saved and then left, rather than left and saved in the background: if the
                // write fails the user is still on the page that can say so.
                if (state.canSave) viewModel.saveName()
                onDone()
            },
            enabled = !state.savingName && !state.savingImage,
            shape = KickoffShapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            if (state.savingName) {
                KickoffLoader(size = 20.dp, ringColor = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text("Done", style = MaterialTheme.typography.titleMedium)
            }
        }

        TextButton(onClick = onDone, enabled = !state.savingName) {
            Text("Skip for now")
        }
        Spacer(Modifier.height(24.dp))
    }
}
