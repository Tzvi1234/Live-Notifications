package com.tzvi.kickoff.feature.auth

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tzvi.kickoff.data.auth.AuthRepository
import com.tzvi.kickoff.data.auth.AuthState
import com.tzvi.kickoff.di.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class ProfileUiState(
    val signedIn: Boolean = false,
    val accountsAvailable: Boolean = false,
    val email: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val imageUrl: String? = null,
    val savingName: Boolean = false,
    val savingImage: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
) {
    val canSave: Boolean
        get() = !savingName && (firstName.isNotBlank() || lastName.isNotBlank())
}

/** What the user has typed but not yet saved; null means "still whatever Clerk holds". */
private data class ProfileEdits(
    val firstName: String? = null,
    val lastName: String? = null,
    val savingName: Boolean = false,
    val savingImage: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val auth: AuthRepository,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val edits = MutableStateFlow(ProfileEdits())

    val uiState: StateFlow<ProfileUiState> =
        combine(edits, auth.state) { local, account ->
            val user = (account as? AuthState.SignedIn)?.user
            ProfileUiState(
                signedIn = user != null,
                accountsAvailable = account != AuthState.NotConfigured,
                email = user?.let { signedIn ->
                    val addresses = signedIn.emailAddresses.orEmpty()
                    addresses.firstOrNull { it.id == signedIn.primaryEmailAddressId }
                        ?: addresses.firstOrNull()
                }?.emailAddress.orEmpty(),
                firstName = local.firstName ?: user?.firstName.orEmpty(),
                lastName = local.lastName ?: user?.lastName.orEmpty(),
                // Clerk serves a generated avatar until a picture is uploaded, so a URL
                // is always worth drawing; the placeholder icon is for signed-out only.
                imageUrl = user?.imageUrl?.takeIf { it.isNotBlank() },
                savingName = local.savingName,
                savingImage = local.savingImage,
                error = local.error,
                notice = local.notice,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = ProfileUiState(),
        )

    fun onFirstNameChange(value: String) =
        edits.update { it.copy(firstName = value, error = null, notice = null) }

    fun onLastNameChange(value: String) =
        edits.update { it.copy(lastName = value, error = null, notice = null) }

    fun saveName() {
        val state = uiState.value
        if (!state.canSave) return
        viewModelScope.launch {
            edits.update { it.copy(savingName = true, error = null, notice = null) }
            val failure = auth.updateName(state.firstName, state.lastName)
            edits.update {
                it.copy(
                    savingName = false,
                    error = failure,
                    notice = if (failure == null) "Name saved." else null,
                    // Clerk is the record now, so the local edit stops shadowing it.
                    firstName = if (failure == null) null else it.firstName,
                    lastName = if (failure == null) null else it.lastName,
                )
            }
        }
    }

    /**
     * Copies the picked image somewhere Clerk can read it, then uploads it.
     *
     * The photo picker hands back a content URI that this process may read once and only
     * through a ContentResolver; `setProfileImage` wants a `java.io.File`. Copying into
     * the cache is the only bridge between the two, and the copy is deleted either way
     * so a picked photo never lingers in the app's storage.
     */
    fun setProfileImage(uri: Uri) {
        viewModelScope.launch {
            edits.update { it.copy(savingImage = true, error = null, notice = null) }
            val copy = withContext(io) { copyToCache(uri) }
            val failure = if (copy == null) {
                "That image could not be read."
            } else {
                auth.setProfileImage(copy).also { withContext(io) { copy.delete() } }
            }
            edits.update {
                it.copy(
                    savingImage = false,
                    error = failure,
                    notice = if (failure == null) "Picture updated." else null,
                )
            }
        }
    }

    fun signOut(onDone: () -> Unit) {
        viewModelScope.launch {
            val failure = auth.signOut()
            if (failure != null) {
                edits.update { it.copy(error = failure) }
                return@launch
            }
            onDone()
        }
    }

    private fun copyToCache(uri: Uri): File? = runCatching {
        val file = File(context.cacheDir, PROFILE_IMAGE_FILE)
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: return null
        file
    }.getOrNull()

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
        const val PROFILE_IMAGE_FILE = "profile-upload"
    }
}
