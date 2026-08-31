package com.tzvi.kickoff.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What this device will actually do with a Live Update.
 *
 * Promotion fails silently: there is no exception and no log when a notification is
 * rejected, so every decision is made against a real capability check here rather than
 * against an assumption, and the result is read back after posting.
 */
@Singleton
class LiveUpdateCapability @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val notificationManager: NotificationManager?
        get() = context.getSystemService(NotificationManager::class.java)

    /** `ProgressStyle` renders from API 36; below that it degrades to a plain card. */
    val supportsProgressStyle: Boolean
        get() = Build.VERSION.SDK_INT >= 36

    /**
     * `setRequestPromotedOngoing` and `POST_PROMOTED_NOTIFICATIONS` landed in API **36.1**,
     * a minor SDK level that `SDK_INT` cannot see - it reports 36 for both Android 16 and
     * Android 16 QPR1. `SDK_INT_FULL` carries the minor version, and is itself only
     * present from API 36, hence the guard.
     */
    val supportsPromotion: Boolean
        get() = when {
            Build.VERSION.SDK_INT >= 37 -> true
            Build.VERSION.SDK_INT == 36 -> Api36Impl.isAtLeastQpr1()
            else -> false
        }

    /** `MetricStyle` - three metrics, which is exactly [home score][clock][away score]. */
    val supportsMetricStyle: Boolean
        get() = Build.VERSION.SDK_INT >= 37

    /**
     * Whether the user has left promoted notifications switched on for this app. The
     * permission itself is `normal|appops`: granted at install, revocable in Settings,
     * with no runtime request flow to build.
     */
    fun canPostPromoted(): Boolean {
        if (!supportsPromotion) return false
        val manager = notificationManager ?: return false
        return runCatching { Api36Impl.canPostPromotedNotifications(manager) }.getOrDefault(false)
    }

    /** True once the system has actually promoted a posted notification. */
    fun wasPromoted(notification: Notification): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        return (notification.flags and FLAG_PROMOTED_ONGOING) != 0
    }

    /**
     * Deep link to the per-app promoted-notification toggle.
     *
     * Note the constant is `ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS`; Google's own
     * Live Update guide cites `ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS`, which does not
     * exist in `android.provider.Settings`. The docs also warn that a matching activity
     * may be absent, hence the resolve check.
     */
    fun promotionSettingsIntent(): Intent? {
        if (Build.VERSION.SDK_INT < 36) return null
        val intent = Intent(ACTION_PROMOTION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent.takeIf { it.resolveActivity(context.packageManager) != null }
    }

    /**
     * True when the device can put a live card on surfaces beyond the shade - the
     * status bar chip, the lock screen, and on hardware that supports it the
     * always-on display. There is no API to request or query AOD placement itself;
     * being promoted is the only lever an app has.
     */
    fun canReachAmbientSurfaces(): Boolean = canPostPromoted()

    @RequiresApi(36)
    private object Api36Impl {
        fun canPostPromotedNotifications(manager: NotificationManager): Boolean =
            manager.canPostPromotedNotifications()

        /**
         * `SDK_INT_FULL` encodes `major * 100_000 + minor`, so Android 16 is 3_600_000 and
         * Android 16 QPR1 is 3_600_001 (`VERSION_CODES_FULL.BAKLAVA_1`). Comparing against
         * the constant avoids re-deriving that arithmetic - dividing the minor out by the
         * wrong power of ten silently reports every device as pre-QPR1, which disables
         * promotion everywhere without an error.
         */
        fun isAtLeastQpr1(): Boolean =
            Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1
    }

    private companion object {
        /** `Notification.FLAG_PROMOTED_ONGOING`, inlined so it compiles below API 36. */
        const val FLAG_PROMOTED_ONGOING = 0x00040000

        /** `Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS`. */
        const val ACTION_PROMOTION_SETTINGS = "android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS"
    }
}
