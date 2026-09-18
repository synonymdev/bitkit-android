package to.bitkit.ui

import android.content.Context
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.navigation
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.ui.components.Sheet
import to.bitkit.ui.screens.wallets.receive.ReceiveRoute
import to.bitkit.viewmodels.TransferEffect
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class ContentViewTest {
    @Test
    fun `pending profile opens once and rearms after completion or cold start`() {
        val navigation = PubkyProfileSetupNavigation()

        assertTrue(navigation.shouldNavigate(true, true, true, true))
        assertFalse(navigation.shouldNavigate(true, true, true, false))
        assertFalse(navigation.shouldNavigate(true, true, true, true))
        assertFalse(navigation.shouldNavigate(true, false, true, true))
        assertTrue(navigation.shouldNavigate(true, true, true, true))
        assertTrue(PubkyProfileSetupNavigation().shouldNavigate(true, true, true, true))
    }

    @Test
    fun `pending profile waits for auth feature and sheet gates`() {
        val navigation = PubkyProfileSetupNavigation()

        assertFalse(navigation.shouldNavigate(false, true, true, true))
        assertFalse(navigation.shouldNavigate(true, true, false, true))
        assertFalse(navigation.shouldNavigate(true, true, true, false))
        assertTrue(navigation.shouldNavigate(true, true, true, true))
    }

    @Test
    fun `spending start route uses intro until seen`() {
        assertEquals(Routes.SpendingIntro, transferSpendingStartRoute(hasSeenSpendingIntro = false))
        assertEquals(Routes.SpendingAmount, transferSpendingStartRoute(hasSeenSpendingIntro = true))
    }

    @Test
    fun `hardware spending start route keeps device id after intro`() {
        val deviceId = "trezor-1"

        assertEquals(Routes.SpendingIntroHw(deviceId), transferSpendingStartRoute(false, deviceId))
        assertEquals(Routes.SpendingAmountHw(deviceId), transferSpendingStartRoute(true, deviceId))
    }

    @Test
    fun `transfer effect destinations cover funding paid and hw signed`() {
        assertEquals(Routes.SettingUp, transferEffectDestination(TransferEffect.OnSpendingFundingPaid))
        assertEquals(Routes.SpendingHwSigned, transferEffectDestination(TransferEffect.OnHwTxSigned))
        assertNull(transferEffectDestination(TransferEffect.OnOrderCreated))
    }

    @Test
    fun `a handled root screen link dismisses the open sheet`() {
        val result = shouldDismissSheetForScreenLink(handled = true, currentSheet = Sheet.Receive())

        assertTrue(result)
    }

    @Test
    fun `a rejected screen link leaves the open sheet alone`() {
        val result = shouldDismissSheetForScreenLink(handled = false, currentSheet = Sheet.Receive())

        assertFalse(result)
    }

    @Test
    fun `a handled root screen link with no sheet open dismisses nothing`() {
        val result = shouldDismissSheetForScreenLink(handled = true, currentSheet = null)

        assertFalse(result)
    }

    @Test
    fun `receive presentation key changes only between sheet presentations`() {
        val sheet = Sheet.Receive()
        val samePresentation = sheet.copy(route = ReceiveRoute.Amount)
        val nextPresentation = Sheet.Receive()

        assertEquals(receiveSheetPresentationKey(sheet), receiveSheetPresentationKey(samePresentation))
        assertFalse(receiveSheetPresentationKey(sheet) == receiveSheetPresentationKey(nextPresentation))
    }

    @Test
    fun `savings transfer completion returns home and drops spending from back stack`() {
        val navController = transferNavController()
        navController.navigateTo(Routes.Spending)
        navController.navigateToTransferSavingsAvailability()
        navController.navigateTo(Routes.SavingsProgress)

        navController.navigateOnSavingsTransferExit()

        assertTrue(navController.currentDestination?.hasRoute<Routes.Home>() == true)
        assertNull(navController.previousBackStackEntry)
    }

    @Test
    fun `savings transfer exit leaves a screen opened on top of the flow alone`() {
        val navController = transferNavController()
        navController.navigateToTransferSavingsAvailability()
        navController.navigateTo(Routes.SavingsProgress)
        navController.navigateTo(Routes.Settings)

        navController.navigateOnSavingsTransferExit()

        assertTrue(navController.currentDestination?.hasRoute<Routes.Settings>() == true)
    }

    @Test
    fun `savings transfer exit does nothing once the transfer flow is gone`() {
        val navController = transferNavController()
        navController.navigateTo(Routes.Spending)
        navController.navigateToTransferSavingsAvailability()
        navController.navigateTo(Routes.SavingsProgress)
        navController.navigateOnSavingsTransferExit()
        navController.navigateTo(Routes.Settings)

        navController.navigateOnSavingsTransferExit()

        assertTrue(navController.currentDestination?.hasRoute<Routes.Settings>() == true)
    }

    private fun transferNavController(): NavHostController =
        NavHostController(ApplicationProvider.getApplicationContext<Context>()).apply {
            navigatorProvider.addNavigator(ComposeNavigator())
            graph = createGraph(startDestination = Routes.Home) {
                composable<Routes.Home> {}
                composable<Routes.Spending> {}
                composable<Routes.Settings> {}
                navigation<Routes.TransferRoot>(startDestination = Routes.SavingsAvailability) {
                    composable<Routes.SavingsAvailability> {}
                    composable<Routes.SavingsProgress> {}
                }
            }
        }
}
