package to.bitkit.wear.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.LocalAmbientModeManager
import androidx.wear.compose.foundation.rememberAmbientModeManager
import androidx.wear.compose.material3.AppScaffold
import dagger.hilt.android.AndroidEntryPoint
import to.bitkit.wear.data.AmbientMonitor
import to.bitkit.wear.ui.components.BezelLoading
import to.bitkit.wear.ui.components.isAmbient
import to.bitkit.wear.ui.screens.start.StartErrorScreen
import to.bitkit.wear.ui.theme.BitkitWearTheme
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    companion object {
        /** How long the splash screen may stay while the first screen is picked; the loading ring takes over. */
        private val SPLASH_LIMIT = 1.seconds
    }

    @Inject
    lateinit var ambientMonitor: AmbientMonitor

    private val startViewModel: StartViewModel by viewModels()

    /** What the intent that opened the app asks for, until the navigation shows it. */
    private var launchRequest by mutableStateOf<LaunchRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        // A recreated activity has shown its intent's request already.
        if (savedInstanceState == null) launchRequest = LaunchRequest.from(intent)
        val splashStart = TimeSource.Monotonic.markNow()
        splashScreen.setKeepOnScreenCondition {
            startViewModel.uiState.value.isStarting && splashStart.elapsedNow() < SPLASH_LIMIT
        }
        setContent {
            BitkitWearTheme {
                CompositionLocalProvider(LocalAmbientModeManager provides rememberAmbientModeManager()) {
                    val isAmbient = isAmbient()
                    SideEffect { ambientMonitor.set(isAmbient) }
                    AppScaffold(
                        modifier = Modifier.semantics {
                            testTagsAsResourceId = true // see https://github.com/appium/appium/issues/15138
                        }
                    ) {
                        val uiState by startViewModel.uiState.collectAsStateWithLifecycle()
                        uiState.startRoute?.let {
                            WearNavHost(
                                startRoute = it,
                                launchRequest = launchRequest,
                                onLaunchRequestHandle = { launchRequest = null },
                            )
                        }
                        if (uiState.isStarting) BezelLoading()
                        if (uiState.isRecoveryFailed) {
                            StartErrorScreen(
                                isLoading = uiState.isLoading,
                                onRetryClick = startViewModel::onRetryClick,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        LaunchRequest.from(intent)?.let { launchRequest = it }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The keyguard covers the window without stopping the activity, so regaining focus is how an unlock shows.
        if (hasFocus) startViewModel.onFocusGain()
    }
}
