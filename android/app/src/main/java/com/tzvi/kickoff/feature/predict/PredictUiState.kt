package com.tzvi.kickoff.feature.predict

import com.tzvi.kickoff.core.model.ChatMessage
import com.tzvi.kickoff.core.model.GroupFixture
import com.tzvi.kickoff.core.model.GroupMember
import com.tzvi.kickoff.core.model.PredictGroup

enum class PredictTab(val label: String) {
    FIXTURES("Guesses"),
    TABLE("Table"),
    CHAT("Chat"),
}

/**
 * Why the screen has nothing to show, which is three completely different problems.
 *
 * Signed out is not an error and must not read like one: the rest of the app works
 * perfectly well without an account and this is the one corner that cannot.
 */
enum class PredictBlocker {
    NEEDS_ACCOUNT,
    NEEDS_SERVER,
    NO_GROUPS,
}

data class PredictUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val blocker: PredictBlocker? = null,
    val groups: List<PredictGroup> = emptyList(),
    val selected: PredictGroup? = null,
    val members: List<GroupMember> = emptyList(),
    val fixtures: List<GroupFixture> = emptyList(),
    val chat: List<ChatMessage> = emptyList(),
    val tab: PredictTab = PredictTab.FIXTURES,
    /** Guesses the user has moved but not yet sent, keyed by match. */
    val drafts: Map<Long, Pair<Int, Int>> = emptyMap(),
    /** Matches whose guess is in flight, so their card can show it. */
    val saving: Set<Long> = emptySet(),
    val errorMessage: String? = null,
    val creating: Boolean = false,
    val joining: Boolean = false,
) {
    /** The one match to put at the top of the table: whichever of the group's is in play. */
    val liveFixture: GroupFixture?
        get() = fixtures.firstOrNull { it.isLive }

    val openFixtures: List<GroupFixture> get() = fixtures.filter { it.isOpen }

    val settledFixtures: List<GroupFixture> get() = fixtures.filterNot { it.isOpen }

    /** What the stepper should show: the unsent draft if there is one, else what was sent. */
    fun draftFor(fixture: GroupFixture): Pair<Int, Int> =
        drafts[fixture.matchId]
            ?: fixture.myPrediction?.let { it.home to it.away }
            ?: (0 to 0)

    fun isDirty(fixture: GroupFixture): Boolean {
        val draft = drafts[fixture.matchId] ?: return false
        val sent = fixture.myPrediction ?: return true
        return draft != (sent.home to sent.away)
    }
}
