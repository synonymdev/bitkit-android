package to.bitkit.ui.sheets

import android.content.Context
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.ui.navigateTo
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class SendSheetNavigationTest {
    @Test
    fun `pending clears earlier send screens after prepared amount review`() {
        for (start in listOf(SendRoute.Confirm, SendRoute.ContactSelect, SendRoute.Amount)) {
            val navController = NavHostController(ApplicationProvider.getApplicationContext<Context>()).apply {
                navigatorProvider.addNavigator(ComposeNavigator())
                graph = createGraph(startDestination = start) {
                    composable<SendRoute.Confirm> {}
                    composable<SendRoute.ContactSelect> {}
                    composable<SendRoute.Amount> {}
                    composable<SendRoute.Pending> {}
                }
            }
            navController.navigateToSendAmount(start)
            navController.navigateTo(SendRoute.Confirm)
            navController.navigateToSendPending(
                SendRoute.Pending(paymentHash = "payment", amount = 1000, walletId = "wallet0")
            )
            assertNull(navController.previousBackStackEntry)
            navController.popBackStack()
            assertNull(navController.currentDestination)
        }
    }

    @Test
    fun `amount review discards confirmations while contact payments retain their previous screen`() {
        for (start in listOf(SendRoute.Confirm, SendRoute.Amount, SendRoute.ContactSelect)) {
            val navController = NavHostController(ApplicationProvider.getApplicationContext<Context>()).apply {
                navigatorProvider.addNavigator(ComposeNavigator())
                graph = createGraph(startDestination = start) {
                    composable<SendRoute.Confirm> {}
                    composable<SendRoute.ContactSelect> {}
                    composable<SendRoute.Amount> {}
                    composable<SendRoute.UsdtReview> {}
                }
            }
            navController.navigateToSendAmount(start)
            navController.navigateTo(SendRoute.UsdtReview)
            assertTrue(navController.popBackStack())
            assertTrue(navController.currentDestination?.hasRoute<SendRoute.Amount>() == true)
            repeat(2) {
                navController.navigateTo(SendRoute.Confirm)
                navController.navigateToSendAmount(start)
                assertTrue(navController.currentDestination?.hasRoute<SendRoute.Amount>() == true)
            }
            navController.popBackStack()
            if (start == SendRoute.ContactSelect) {
                assertTrue(navController.currentDestination?.hasRoute<SendRoute.ContactSelect>() == true)
            } else {
                assertNull(navController.currentDestination)
            }
        }
    }
}
