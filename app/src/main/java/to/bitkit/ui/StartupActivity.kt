package to.bitkit.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint

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
                val viewModel = hiltViewModel<WelcomeViewModel>()
                WelcomeScreen(viewModel) {
                    launchMainActivity()
                }
            }
        }
    }

    private fun launchMainActivity() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        })
        finish()
    }
}
