package com.tzvi.kickoff.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tzvi.kickoff.notifications.LiveMatchService

/** Fires at T-minus-lead and hands the match to the foreground service. */
class MatchAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PRE_MATCH) return
        val matchId = intent.getLongExtra(EXTRA_MATCH_ID, -1L)
        if (matchId <= 0) return
        LiveMatchService.track(context, matchId)
    }

    companion object {
        const val ACTION_PRE_MATCH = "com.tzvi.kickoff.action.PRE_MATCH"
        const val EXTRA_MATCH_ID = "match_id"
    }
}
