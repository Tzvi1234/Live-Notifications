package com.tzvi.kickoff.feature.island

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tzvi.kickoff.core.model.IslandCutout
import com.tzvi.kickoff.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Nothing is written while the sliders move.
 *
 * The screen edits a draft and only commits on "Save", so a user who drags the circle
 * across the display and then backs out still has the island where they left it.
 */
@HiltViewModel
class IslandCalibrationViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    /** Null until the first read lands, which is what the screen seeds its draft from. */
    val stored: StateFlow<IslandCutout?> = settings.islandCutout.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
        initialValue = null,
    )

    fun save(cutout: IslandCutout) {
        viewModelScope.launch { settings.setIslandCutout(cutout) }
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
