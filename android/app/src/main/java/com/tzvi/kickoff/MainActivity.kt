package com.tzvi.kickoff

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tzvi.kickoff.ui.KickoffApp
import com.tzvi.kickoff.ui.theme.KickoffTheme
import com.tzvi.kickoff.ui.theme.shouldUseDarkTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private var deepLink by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        enableEdgeToEdge()
        // The island lays itself out around the camera hole, which means its window has to
        // be allowed up into the row the hole is in rather than being pushed under it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        super.onCreate(savedInstanceState)

        deepLink = intent?.data

        // The launch icon animation and the first composed frame are one continuous
        // movement: the splash is held until settings have loaded (so the app never
        // flashes the wrong theme or start destination), then the icon lifts and fades
        // out while the app fades in underneath it.
        splash.setKeepOnScreenCondition { viewModel.uiState.value.loading }
        splash.setOnExitAnimationListener { provider ->
            val icon: View = provider.iconView
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(icon, View.SCALE_X, 1f, 1.18f),
                    ObjectAnimator.ofFloat(icon, View.SCALE_Y, 1f, 1.18f),
                    ObjectAnimator.ofFloat(icon, View.ALPHA, 1f, 0f),
                    ObjectAnimator.ofFloat(provider.view, View.ALPHA, 1f, 0f),
                )
                duration = 320
                interpolator = AccelerateInterpolator()
                doOnEnd { provider.remove() }
                start()
            }
        }

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            KickoffTheme(
                darkTheme = shouldUseDarkTheme(state.settings.darkTheme),
                dynamicColor = state.settings.useDynamicColor,
            ) {
                KickoffApp(
                    state = state,
                    deepLink = deepLink,
                    onDeepLinkHandled = { deepLink = null },
                )
            }
        }
    }

    /** The launcher activity is singleTask, so a notification tap arrives here. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLink = intent.data
    }

    private fun AnimatorSet.doOnEnd(action: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) = action()
        })
    }
}
