package to.bitkit.ui.screens.wallets.usdt

import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import androidx.navigation.toRoute
import com.synonym.bitkitcore.UsdtDestination
import com.synonym.bitkitcore.UsdtQuote
import org.junit.Rule
import org.junit.Test
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.Routes
import to.bitkit.ui.navigateToUsdtPayment
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@ComposeUi
class UsdtPaymentNavigationTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun paymentProgressSurvivesRecreationWithoutRestoringAnEditableDraftAfterProcessDeath() {
        val restoration = StateRestorationTester(composeTestRule)
        var sendState = mutableStateOf(UsdtSendState())
        lateinit var page: MutableState<UsdtPage>
        restoration.setContent {
            val state by remember { sendState }
            page = rememberUsdtPage("owned recipient", state)
            Text(page.value.name)
        }
        restoration.emulateSavedInstanceStateRestore()
        composeTestRule.onNodeWithText(UsdtPage.AMOUNT.name).assertExists()

        val quote = UsdtQuote(
            id = "payment", recipient = "owned recipient", destination = UsdtDestination.ARBITRUM,
            bridgeProvider = null, amount = 1u, receivedAmount = 1u, maximumFee = 1u, expiresAt = 100u,
        )
        for (state in listOf(
            UsdtSendState(quote = quote, busy = true),
            UsdtSendState(quote = quote, busy = true, submitted = true),
            UsdtSendState(quote = quote, submitted = true),
        )) {
            composeTestRule.runOnIdle {
                page.value = UsdtPage.AMOUNT
                sendState.value = state
            }
            restoration.emulateSavedInstanceStateRestore()
            composeTestRule.onNodeWithText(UsdtPage.AMOUNT.name).assertExists()

            composeTestRule.runOnIdle { sendState = mutableStateOf(UsdtSendState()) }
            restoration.emulateSavedInstanceStateRestore()
            composeTestRule.onNodeWithText(UsdtPage.WALLET.name).assertExists()
        }
    }

    @Test
    fun dismissedPaymentLinksOpenFreshEntriesWithCurrentArguments() {
        lateinit var navController: TestNavHostController
        lateinit var page: MutableState<UsdtPage>
        composeTestRule.setContent {
            val context = LocalContext.current
            val controller = remember {
                TestNavHostController(context).apply {
                    navigatorProvider.addNavigator(ComposeNavigator())
                }
            }
            navController = controller
            NavHost(navController = controller, startDestination = Routes.Home) {
                composable<Routes.Home> { Text("Home") }
                composable<Routes.UsdtPayment> { entry ->
                    val payment = entry.toRoute<Routes.UsdtPayment>()
                    page = rememberUsdtPage(payment.recipient, UsdtSendState())
                    Text("${page.value} ${payment.recipient} ${payment.amount}")
                }
            }
        }
        val first = Routes.UsdtPayment("first recipient", "1")
        composeTestRule.runOnIdle { navController.navigateToUsdtPayment(first) }
        val oldId = navController.currentBackStackEntry?.id
        for (payment in listOf(first, Routes.UsdtPayment("second recipient", "2.5"))) {
            composeTestRule.runOnIdle { page.value = UsdtPage.WALLET }
            composeTestRule.runOnIdle { navController.navigateToUsdtPayment(payment) }
            composeTestRule.runOnIdle {
                assertNotEquals(oldId, navController.currentBackStackEntry?.id)
                assertEquals(payment, navController.currentBackStackEntry?.toRoute<Routes.UsdtPayment>())
                assertEquals(UsdtPage.AMOUNT, page.value)
            }
        }
        composeTestRule.runOnIdle {
            navController.popBackStack()
            assertTrue(navController.currentDestination?.hasRoute<Routes.Home>() == true)
        }
    }
}
