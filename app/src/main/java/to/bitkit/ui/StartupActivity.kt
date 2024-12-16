package to.bitkit.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import to.bitkit.ui.onboarding.TermsOfUseScreen
import to.bitkit.ui.theme.AppThemeSurface

@AndroidEntryPoint
class StartupActivity : ComponentActivity() {
    private val viewModel by viewModels<StartupViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            if (viewModel.uiState.hasWallet) {
                launchMainActivity()
            } else {
                AppThemeSurface {
                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = StartupRoutes.TERMS) {
                        composable(StartupRoutes.TERMS) {
                            TermsOfUseScreen(
                                onNavigateToIntro = {
                                    navController.navigate(StartupRoutes.INTRO)
                                }
                            )
                        }
                        // TODO replace with carousel
                        composable(StartupRoutes.INTRO) {
                            val viewModel = hiltViewModel<WelcomeViewModel>()
                            WelcomeScreen(viewModel) {
                                launchMainActivity(isInitializingWallet = true)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun launchMainActivity(isInitializingWallet: Boolean = false) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_INIT_WALLET, isInitializingWallet)
        })
        finish()
    }
}

object StartupRoutes {
    const val TERMS = "terms"
    const val INTRO = "intro"
}
