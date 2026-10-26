package to.bitkit.ui.screens.wallets.send

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import to.bitkit.env.Env
import to.bitkit.ui.LocalAppViewModel
import to.bitkit.ui.navigateTo
import to.bitkit.ui.sheets.SendRoute
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.viewmodels.AppViewModel
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

@Config(application = Application::class, sdk = [34])
@RunWith(RobolectricTestRunner::class)
class SendPinCheckScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `system and header back decline recovery and ordinary pin checks once`() {
        val app: AppViewModel = mock()
        whenever(app.pinAttemptsRemaining).thenReturn(MutableStateFlow(Env.PIN_ATTEMPTS))
        lateinit var navController: NavHostController
        var cancellations = 0
        var successes = 0
        val pending = SendRoute.Pending(paymentHash = "payment", amount = 1000, walletId = "wallet0")
        composeRule.setContent {
            AppThemeSurface {
                CompositionLocalProvider(LocalAppViewModel provides app) {
                    navController = rememberNavController()
                    NavHost(navController, startDestination = pending) {
                        composable<SendRoute.Pending> {}
                        composable<SendRoute.RecoveryPinCheck> {
                            SendPinCheckScreen(
                                onBack = {
                                    cancellations++
                                    navController.previousBackStackEntry?.savedStateHandle
                                        ?.set(RECOVERY_PIN_CHECK_RESULT_KEY, false)
                                    navController.popBackStack()
                                },
                                onSuccess = {
                                    successes++
                                    navController.previousBackStackEntry?.savedStateHandle
                                        ?.set(RECOVERY_PIN_CHECK_RESULT_KEY, true)
                                    navController.popBackStack()
                                },
                            )
                        }
                        composable<SendRoute.PinCheck> {
                            SendPinCheckScreen(
                                onBack = {
                                    cancellations++
                                    navController.previousBackStackEntry?.savedStateHandle
                                        ?.set(PIN_CHECK_RESULT_KEY, false)
                                    navController.popBackStack()
                                },
                                onSuccess = {
                                    successes++
                                    navController.previousBackStackEntry?.savedStateHandle
                                        ?.set(PIN_CHECK_RESULT_KEY, true)
                                    navController.popBackStack()
                                },
                            )
                        }
                    }
                }
            }
        }

        var expectedCancellations = 0
        for ((route, resultKey) in listOf(
            SendRoute.RecoveryPinCheck to RECOVERY_PIN_CHECK_RESULT_KEY,
            SendRoute.PinCheck to PIN_CHECK_RESULT_KEY,
        )) {
            for (systemBack in listOf(true, false)) {
                composeRule.runOnIdle { navController.navigateTo(route) }
                composeRule.onNodeWithTag("NavigationBack").assertExists()
                composeRule.waitUntil {
                    navController.currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED
                }
                if (systemBack) {
                    composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
                } else {
                    ShadowSystemClock.advanceBy(1.seconds.toJavaDuration())
                    composeRule.onNodeWithTag("NavigationBack").performClick()
                }
                expectedCancellations++
                composeRule.runOnIdle {
                    assertEquals(
                        expectedCancellations,
                        cancellations,
                        "Expected one cancellation for $route with systemBack=$systemBack",
                    )
                    assertTrue(
                        navController.currentDestination?.hasRoute<SendRoute.Pending>() == true,
                        "Expected Pending after $route with systemBack=$systemBack",
                    )
                    val result = navController.currentBackStackEntry?.savedStateHandle?.remove<Boolean>(resultKey)
                    assertFalse(requireNotNull(result))
                    assertEquals(0, successes)
                }
            }
        }
        verify(app, never()).validatePin(any())

        whenever(app.validatePin("1".repeat(Env.PIN_LENGTH))).thenReturn(true)
        composeRule.runOnIdle { navController.navigateTo(SendRoute.RecoveryPinCheck) }
        repeat(Env.PIN_LENGTH) { composeRule.onNodeWithTag("N1").performClick() }
        composeRule.runOnIdle {
            assertTrue(navController.currentDestination?.hasRoute<SendRoute.Pending>() == true)
            val result = navController.currentBackStackEntry?.savedStateHandle
                ?.get<Boolean>(RECOVERY_PIN_CHECK_RESULT_KEY)
            assertEquals(true, result)
            assertEquals(expectedCancellations, cancellations)
            assertEquals(1, successes)
        }
        verify(app).validatePin("1".repeat(Env.PIN_LENGTH))
    }
}
