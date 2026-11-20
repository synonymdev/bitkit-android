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
import to.bitkit.repositories.SparkState
import to.bitkit.ui.navigateTo
import to.bitkit.viewmodels.SendMethod
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class SendSheetNavigationTest {
    @Test
    fun `payment results stay visible after a stopped-node Spark payment reduces the balance`() {
        val navController = NavHostController(ApplicationProvider.getApplicationContext<Context>()).apply {
            navigatorProvider.addNavigator(ComposeNavigator())
            graph = createGraph(startDestination = SendRoute.Confirm) {
                composable<SendRoute.Confirm> {}
                composable<SendRoute.Pending> {}
                composable<SendRoute.Success> {}
                composable<SendRoute.Error> {}
            }
        }
        var spark = SparkState(isSupported = true, isReady = true, balanceSats = 100_000uL)
        fun waiting() = waitsForNode(
            isNodeRunning = false,
            hasAnyChannels = true,
            hasUsableChannels = false,
            sparkCanPay = sparkCanPayInvoice(SendMethod.LIGHTNING, 60_000uL, spark),
            destination = navController.currentDestination,
        )

        assertFalse(waiting())
        spark = spark.copy(balanceSats = 39_758uL)
        assertTrue(waiting())
        val results = listOf(
            SendRoute.Pending(paymentHash = "payment", amount = 60_000, walletId = "wallet0"),
            SendRoute.Success,
            SendRoute.Error(),
        )
        for (result in results) {
            navController.navigateTo(result)
            assertFalse(waiting())
            assertTrue(navController.popBackStack())
            assertTrue(waiting())
        }
    }

    @Test
    fun `recovery pin holds review only until returning to pending`() {
        val pending = SendRoute.Pending(paymentHash = "payment", amount = 1000, walletId = "wallet0")
        val navController = NavHostController(ApplicationProvider.getApplicationContext<Context>()).apply {
            navigatorProvider.addNavigator(ComposeNavigator())
            graph = createGraph(startDestination = pending) {
                composable<SendRoute.Pending> {}
                composable<SendRoute.Confirm> {}
                composable<SendRoute.PinCheck> {}
                composable<SendRoute.RecoveryPinCheck> {}
                composable<SendRoute.HardwareSign> {}
            }
        }
        val reviewRoutes = listOf(
            SendRoute.RecoveryPinCheck,
            SendRoute.Confirm,
            SendRoute.PinCheck,
            SendRoute.HardwareSign,
        )
        for (route in reviewRoutes) {
            assertFalse(requireNotNull(navController.currentDestination).isPaymentReview())
            navController.navigateTo(route)
            assertTrue(requireNotNull(navController.currentDestination).isPaymentReview())
            assertTrue(navController.popBackStack())
            assertFalse(requireNotNull(navController.currentDestination).isPaymentReview())
        }
    }

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

    @Test
    fun `amount fallback after a pay from switch clears the replacing confirmation`() {
        val switches = listOf(SendRoute.UsdtReview to SendRoute.Confirm, SendRoute.Confirm to SendRoute.UsdtReview)
        for ((start, switched) in switches) {
            val navController = NavHostController(ApplicationProvider.getApplicationContext<Context>()).apply {
                navigatorProvider.addNavigator(ComposeNavigator())
                graph = createGraph(startDestination = start) {
                    composable<SendRoute.Confirm> {}
                    composable<SendRoute.Amount> {}
                    composable<SendRoute.UsdtReview> {}
                }
            }
            navController.navigateTo(switched) { popUpTo(start) { inclusive = true } }
            navController.navigateToSendAmount(start)
            assertTrue(navController.currentDestination?.hasRoute<SendRoute.Amount>() == true)
            navController.popBackStack()
            assertNull(navController.currentDestination)
        }
    }
}
