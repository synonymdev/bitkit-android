package to.bitkit.ui.sheets

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.ui.screens.wallets.send.collectConfirmResets
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Config(application = Application::class, sdk = [34])
@RunWith(RobolectricTestRunner::class)
class SendErrorNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navController: NavHostController
    private var isLoading = false
    private var swipe: () -> Unit = {}

    private fun setContent() {
        composeRule.setContent {
            navController = rememberNavController()
            NavHost(navController, startDestination = SendRoute.Confirm) {
                composable<SendRoute.Confirm> { entry ->
                    // Saved like the confirmation's own swipe state
                    var loading by rememberSaveable { mutableStateOf(false) }
                    LaunchedEffect(entry) { entry.savedStateHandle.collectConfirmResets { loading = false } }
                    isLoading = loading
                    swipe = { loading = true }
                }
                composable<SendRoute.Error> {}
            }
        }
        composeRule.runOnUiThread { swipe() }
        composeRule.waitForIdle()
    }

    @Test
    fun `back from a failed swipe returns to a confirmation that takes a swipe`() {
        setContent()
        assertTrue(isLoading)

        composeRule.runOnUiThread { navController.navigateToSendError(SendRoute.Error()) }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { navController.popBackStack() }
        composeRule.waitForIdle()

        assertFalse(isLoading)
    }
}
