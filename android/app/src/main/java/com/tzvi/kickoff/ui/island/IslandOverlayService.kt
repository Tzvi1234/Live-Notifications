package com.tzvi.kickoff.ui.island

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.tzvi.kickoff.MainActivity
import com.tzvi.kickoff.core.model.IslandCutout
import com.tzvi.kickoff.core.model.LiveActivity
import com.tzvi.kickoff.data.repository.FootballRepository
import com.tzvi.kickoff.data.repository.SettingsRepository
import com.tzvi.kickoff.ui.theme.KickoffTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Floats the island over whatever the user is actually doing.
 *
 * This is the part that makes it feel like the iPhone's: a window of our own, added
 * straight to the WindowManager as an application overlay, so the score follows the user
 * out of the app. It needs the "display over other apps" permission, which is a
 * deliberate, revocable, per-app grant - so the feature is opt-in and the in-app island
 * works with nothing granted.
 *
 * A Service is not a LifecycleOwner and Compose refuses to run without one, so this
 * class provides the three owners a ComposeView looks for on its view tree.
 */
@AndroidEntryPoint
class IslandOverlayService :
    Service(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    @Inject lateinit var repository: FootballRepository
    @Inject lateinit var settings: SettingsRepository

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private var cutout: IslandCutout = IslandCutout.Unset

    /**
     * True only after a real show() start. The foreground/background signals reach this
     * service by startService, which CREATES it if nothing was running - and a service
     * created by its own bookkeeping signal must not conjure an island the user never
     * turned on.
     */
    private var active = false

    private val expandedState = mutableStateOf(false)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        windowManager = getSystemService(WindowManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!canDrawOverlay(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_HIDE -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_APP_FOREGROUND -> {
                if (!active) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                removeOverlay()
            }

            ACTION_APP_BACKGROUND -> {
                if (!active) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                lifecycleScope.launch { start() }
            }

            // The calibration decides the window's own geometry, so it has to be read
            // before the window is added rather than observed from inside the content.
            else -> {
                active = true
                lifecycleScope.launch { start() }
            }
        }
        return START_STICKY
    }

    private suspend fun start() {
        cutout = settings.islandCutout.first()
        if (!appVisible) showOverlay()
    }

    private fun showOverlay() {
        if (overlayView != null) return
        val manager = windowManager ?: return

        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@IslandOverlayService)
            setViewTreeViewModelStoreOwner(this@IslandOverlayService)
            setViewTreeSavedStateRegistryOwner(this@IslandOverlayService)
            setContent {
                // Held by the service, not by the composition: the window's own size and
                // position change with it, so the two must never be able to disagree.
                var expanded by expandedState
                var activity by androidx.compose.runtime.remember {
                    mutableStateOf<LiveActivity.MatchActivity?>(null)
                }

                androidx.compose.runtime.LaunchedEffect(Unit) {
                    // The island always shows the first live match. collectLatest means a
                    // new one cancels the previous match's event subscription instead of
                    // leaking it for as long as the overlay is up.
                    repository.liveMatches.collectLatest { matches ->
                        val match = matches.firstOrNull()
                        if (match == null) {
                            // No live match left: the overlay has nothing to say, so it goes.
                            stopSelf()
                            return@collectLatest
                        }
                        repository.observeEvents(match.id).collectLatest { events ->
                            activity = LiveActivity.MatchActivity(
                                match = match,
                                stage = LiveActivity.MatchActivity.Stage.LIVE,
                                lineups = null,
                                recentEvents = events,
                                statistics = null,
                            )
                        }
                    }
                }

                KickoffTheme(darkTheme = true) {
                    DynamicIsland(
                        activity = activity,
                        expanded = expanded,
                        cutout = cutout,
                        // Collapsed, the window itself is centred on the hole and only as
                        // wide as the pill, so the content must not offset itself again.
                        cameraCenterX = if (expanded) cameraCenterXDp() else null,
                        // Taps in the status-bar band are SystemUI's; the strip below the
                        // pill is where a tap can actually land.
                        touchPadBelow = TOUCH_PAD_DP.dp,
                        onToggle = {
                            expanded = !expanded
                            // A collapsed pill must not swallow taps meant for the app
                            // underneath; an expanded card must be able to take them.
                            updateTouchability(expanded)
                        },
                        onOpenMatch = { matchId ->
                            startActivity(
                                Intent(this@IslandOverlayService, MainActivity::class.java)
                                    .setAction(Intent.ACTION_VIEW)
                                    .setData(Uri.parse("kickoff://match/$matchId"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                            stopSelf()
                        },
                        onDismiss = { stopSelf() },
                    )
                }
            }
        }

        runCatching { manager.addView(view, layoutParams(expanded = false)) }
            .onSuccess { overlayView = view }
            .onFailure { stopSelf() }

        // Nobody has calibrated yet: take Android's own word for where the hole is rather
        // than floating the pill over it. This is a guess, and Settings says so - but a
        // guess placed on the camera beats a pill parked on top of it.
        if (!cutout.enabled) {
            view.post {
                val detected = IslandCutoutDetector.detect(view) ?: return@post
                cutout = detected
                runCatching {
                    windowManager?.updateViewLayout(view, layoutParams(expandedState.value))
                }
            }
        }
    }

    private fun removeOverlay() {
        val view = overlayView ?: return
        runCatching { windowManager?.removeView(view) }
        view.setViewTreeLifecycleOwner(null)
        overlayView = null
        expandedState.value = false
    }

    private fun updateTouchability(expanded: Boolean) {
        val view = overlayView ?: return
        runCatching { windowManager?.updateViewLayout(view, layoutParams(expanded)) }
    }

    private fun layoutParams(expanded: Boolean): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            if (expanded) WindowManager.LayoutParams.MATCH_PARENT
            else WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            if (cutout.enabled) {
                // Anchored to the left edge and moved by hand, because the hole is not
                // necessarily in the middle of the display.
                gravity = Gravity.TOP or Gravity.START
                x = if (expanded) 0 else collapsedLeftPx()
                y = pillTopPx()
            } else {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = OVERLAY_TOP_MARGIN_PX
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Sit beside the camera cutout rather than being pushed below it.
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

    // ---- calibrated geometry -------------------------------------------------------
    //
    // A collapsed island keeps its window pill-sized. A full-width window pinned to the
    // top of the screen would take every touch in the status-bar strip with it, and the
    // user would lose the notification shade for as long as a match was live.

    private fun screenWidthPx(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager?.currentWindowMetrics?.bounds?.width()
                ?.takeIf { it > 0 }
                ?.let { return it }
        }
        return resources.displayMetrics.widthPixels
    }

    private fun density(): Float = resources.displayMetrics.density.takeIf { it > 0f } ?: 1f

    private fun collapsedWidthPx(): Int =
        ((SIDE_WIDTH_DP * 2 + cutout.clearanceDp) * density()).toInt()

    private fun collapsedLeftPx(): Int {
        val width = collapsedWidthPx()
        val camera = (screenWidthPx() * cutout.centerXFraction).toInt()
        return (camera - width / 2).coerceIn(0, (screenWidthPx() - width).coerceAtLeast(0))
    }

    private fun pillTopPx(): Int =
        ((cutout.centerYDp - COLLAPSED_HEIGHT_DP / 2f) * density()).toInt().coerceAtLeast(0)

    /** Where the hole falls inside a full-width window: only meaningful when expanded. */
    private fun cameraCenterXDp(): Dp = (screenWidthPx() * cutout.centerXFraction / density()).dp

    override fun onDestroy() {
        overlayView?.let { view ->
            runCatching { windowManager?.removeView(view) }
            (view as View).setViewTreeLifecycleOwner(null)
        }
        overlayView = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_HIDE = "com.tzvi.kickoff.action.HIDE_ISLAND"
        private const val ACTION_APP_FOREGROUND = "com.tzvi.kickoff.action.APP_FOREGROUND"
        private const val ACTION_APP_BACKGROUND = "com.tzvi.kickoff.action.APP_BACKGROUND"
        private const val OVERLAY_TOP_MARGIN_PX = 24
        private const val SIDE_WIDTH_DP = 74
        private const val COLLAPSED_HEIGHT_DP = 36
        private const val TOUCH_PAD_DP = 24

        fun canDrawOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

        /** Sends the user to the system page where the overlay grant lives. */
        fun permissionIntent(context: Context): Intent =
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun show(context: Context) {
            if (!canDrawOverlay(context)) return
            context.startService(Intent(context, IslandOverlayService::class.java))
        }

        fun hide(context: Context) {
            context.startService(
                Intent(context, IslandOverlayService::class.java).setAction(ACTION_HIDE),
            )
        }

        /**
         * Called from MainActivity's onStart/onStop.
         *
         * Deliberately fire-and-forget: if the service is not running these do nothing,
         * which is exactly right - there is no island to take down.
         */
        /**
         * Held here, not on the instance: turning the float switch on starts a FRESH
         * service instance, and a fresh instance that cannot ask "is the app in front
         * right now?" draws the island over the very Settings screen that launched it.
         */
        @Volatile
        private var appVisible = false

        fun appForeground(context: Context) {
            appVisible = true
            signal(context, ACTION_APP_FOREGROUND)
        }

        fun appBackground(context: Context) {
            appVisible = false
            signal(context, ACTION_APP_BACKGROUND)
        }

        private fun signal(context: Context, action: String) {
            if (!canDrawOverlay(context)) return
            runCatching {
                context.startService(
                    Intent(context, IslandOverlayService::class.java).setAction(action),
                )
            }
        }
    }
}
