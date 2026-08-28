package to.bitkit.ui.screens.wallets.receive

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.navigateTo
import to.bitkit.ui.components.SheetHost
import to.bitkit.ui.theme.AppThemeSurface
import kotlin.reflect.KClass
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@ComposeUi
class ReceiveNavigationTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var navController: TestNavHostController
    private var sheetDismissed = false

    @Test
    fun deepLinkedAmountReturnsToQr() {
        setGraph(ReceiveRoute.Amount)

        assertOn(ReceiveRoute.Amount::class)
        pressBack()
        assertOn(ReceiveRoute.QR::class)
        assertFalse(sheetDismissed)
    }

    @Test
    fun paymentRequestDetailsIsTheRoot() {
        setGraph(ReceiveRoute.PaymentRequestDetails)

        assertOn(ReceiveRoute.PaymentRequestDetails::class)
        pressBack()
        composeTestRule.waitForIdle()
        assertTrue(sheetDismissed)
    }

    @Test
    fun sentPaymentRequestDoesNotReturnToTheForm() {
        setGraph(ReceiveRoute.PaymentRequestDetails)
        composeTestRule.runOnIdle { navController.navigateTo(ReceiveRoute.PaymentRequestRecipient) }
        assertOn(ReceiveRoute.PaymentRequestRecipient::class)

        composeTestRule.runOnIdle { navController.navigateToPaymentRequestSent() }
        assertOn(ReceiveRoute.PaymentRequestSent::class)
        pressBack()
        composeTestRule.waitForIdle()
        assertTrue(sheetDismissed)
    }

    private fun setGraph(startRoute: ReceiveRoute) {
        sheetDismissed = false
        composeTestRule.setContent { TestGraph(startRoute) }
        composeTestRule.waitForIdle()
    }

    private fun assertOn(route: KClass<out ReceiveRoute>) {
        composeTestRule.waitForIdle()
        assertTrue(navController.currentDestination?.hasRoute(route) == true)
    }

    @Composable
    private fun TestGraph(startRoute: ReceiveRoute) {
        val context = LocalContext.current
        val controller = remember {
            TestNavHostController(context).apply {
                navigatorProvider.addNavigator(ComposeNavigator())
            }
        }
        navController = controller

        AppThemeSurface {
            SheetHost(
                shouldExpand = true,
                onDismiss = { sheetDismissed = true },
                sheets = {
                    LaunchedEffect(startRoute) { controller.navigateToReceiveStart(startRoute) }
                    NavHost(navController = controller, startDestination = startRoute.rootRoute()) {
                        composable<ReceiveRoute.QR> { Text("QR") }
                        composable<ReceiveRoute.Amount> { Text("Amount") }
                        composable<ReceiveRoute.PaymentRequestDetails> { Text("Details") }
                        composable<ReceiveRoute.PaymentRequestRecipient> { Text("Recipient") }
                        composable<ReceiveRoute.PaymentRequestSent> { Text("Sent") }
                    }
                },
                content = { Text("Content") },
            )
        }
    }
}
