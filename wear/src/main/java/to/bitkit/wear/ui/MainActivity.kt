package to.bitkit.wear.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.LocalAmbientModeManager
import androidx.wear.compose.foundation.rememberAmbientModeManager
import androidx.wear.compose.material3.AppScaffold
import dagger.hilt.android.AndroidEntryPoint
import to.bitkit.wear.ui.theme.BitkitWearTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val startViewModel: StartViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { startViewModel.uiState.value.startRoute == null }
        setContent {
            BitkitWearTheme {
                CompositionLocalProvider(LocalAmbientModeManager provides rememberAmbientModeManager()) {
                    AppScaffold(
                        modifier = Modifier.semantics {
                            testTagsAsResourceId = true // see https://github.com/appium/appium/issues/15138
                        }
                    ) {
                        val uiState by startViewModel.uiState.collectAsStateWithLifecycle()
                        uiState.startRoute?.let { WearNavHost(startRoute = it) }
                    }
                }
            }
        }
    }
}
