package com.tzvi.kickoff.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tzvi.kickoff.core.model.PlayerCard
import com.tzvi.kickoff.data.repository.FootballRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Who to open the sheet for, and which match to read their line from. */
data class PlayerRequest(
    val playerId: Int,
    val name: String,
    val photoUrl: String?,
    val teamName: String?,
    val matchId: Long?,
)

data class PlayerSheetState(
    val loading: Boolean = true,
    val card: PlayerCard? = null,
    val error: String? = null,
)

@HiltViewModel
class PlayerSheetViewModel @Inject constructor(
    private val repository: FootballRepository,
) : ViewModel() {

    private val mutable = MutableStateFlow(PlayerSheetState())
    val state: StateFlow<PlayerSheetState> = mutable.asStateFlow()

    private var job: Job? = null

    /**
     * The sheet appears immediately with what the line-up already knew - name, photo, club
     * - and fills in underneath. Waiting on the network before drawing anything would make
     * a tap feel broken on a slow connection, when in fact the answer is already half known.
     */
    fun load(request: PlayerRequest) {
        job?.cancel()
        mutable.value = PlayerSheetState(
            loading = true,
            card = PlayerCard(
                id = request.playerId,
                name = request.name,
                photoUrl = request.photoUrl,
                teamName = request.teamName,
                profile = null,
                match = null,
            ),
        )
        job = viewModelScope.launch {
            try {
                val card = repository.playerCard(
                    playerId = request.playerId,
                    matchId = request.matchId,
                    name = request.name,
                    photoUrl = request.photoUrl,
                    teamName = request.teamName,
                )
                mutable.update { it.copy(loading = false, card = card) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // The header is still worth showing, so the failure only clears the body.
                mutable.update {
                    it.copy(
                        loading = false,
                        error = "Could not read this player's match statistics.",
                    )
                }
            }
        }
    }
}
