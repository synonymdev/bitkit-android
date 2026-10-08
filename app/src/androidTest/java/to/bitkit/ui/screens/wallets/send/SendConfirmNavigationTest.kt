package to.bitkit.ui.screens.wallets.send

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import to.bitkit.data.SettingsStore
import to.bitkit.data.WidgetsStore
import to.bitkit.repositories.ContactPaymentSettingsRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.WidgetsRepo
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.LocalSettingsViewModel
import to.bitkit.ui.sheets.SendRoute
import to.bitkit.ui.sheets.navigateToCoinSelection
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.viewmodels.SendEvent
import to.bitkit.viewmodels.SendMethod
import to.bitkit.viewmodels.SendUiState
import to.bitkit.viewmodels.SettingsViewModel
import javax.inject.Inject
import kotlin.test.assertEquals

@HiltAndroidTest
@ComposeUi
class SendConfirmNavigationTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createComposeRule()

    @Inject lateinit var settingsStore: SettingsStore
    @Inject lateinit var widgetsStore: WidgetsStore
    @Inject lateinit var pubkyRepo: PubkyRepo
    @Inject lateinit var contactPaymentSettingsRepo: ContactPaymentSettingsRepo
    @Inject lateinit var widgetsRepo: WidgetsRepo

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun pickerBackReenablesSwipeButPendingPaymentKeepsFundingLocked() {
        val state = mutableStateOf(
            SendUiState(amount = 1_000u, isAmountInputValid = true, canSwitchFundingSource = true),
        )
        var swipes = 0
        var fundingSwitches = 0
        var holdConfirmation = false
        composeTestRule.setContent {
            val context = LocalContext.current
            val settings = viewModel {
                SettingsViewModel(context, settingsStore, pubkyRepo, contactPaymentSettingsRepo, widgetsStore, widgetsRepo)
            }
            AppThemeSurface {
                CompositionLocalProvider(
                    LocalSettingsViewModel provides settings,
                    LocalInspectionMode provides true,
                ) {
                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = SendRoute.Confirm) {
                        composable<SendRoute.Confirm> { entry ->
                            SendConfirmScreen(
                                savedStateHandle = entry.savedStateHandle,
                                uiState = state.value,
                                isNodeRunning = true,
                                canAutoStart = false,
                                canGoBack = false,
                                onBack = {},
                                onEvent = { event ->
                                    if (event == SendEvent.SwipeToPay) {
                                        swipes++
                                        if (!holdConfirmation) navController.navigateToCoinSelection()
                                    } else if (event == SendEvent.PaymentMethodSwitch) {
                                        fundingSwitches++
                                    } else {
                                        error("Unexpected send event: $event")
                                    }
                                },
                                onClickAddTag = {},
                                onClickTag = {},
                                onNavigateToPin = { error("Coin selection must not request authentication") },
                            )
                        }
                        composable<SendRoute.CoinSelection> {
                            SendCoinSelectionContent(
                                uiState = CoinSelectionUiState(),
                                onBack = { navController.popBackStack() },
                            )
                        }
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag("SendConfirmToggleDetails").performClick()
        repeat(3) { index ->
            composeTestRule.onNodeWithTag("SendConfirmAssetButton").assertHasClickAction()
            swipeToConfirm()
            composeTestRule.waitUntil(5_000) {
                composeTestRule.onAllNodesWithTag("coin_selection_screen").fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNodeWithTag("continue_button").assertIsNotEnabled()
            composeTestRule.onNodeWithTag("NavigationBack").performClick()
            composeTestRule.onNodeWithTag("SendConfirm").assertIsDisplayed()
            composeTestRule.runOnIdle { assertEquals(index + 1, swipes) }
        }

        composeTestRule.runOnIdle { holdConfirmation = true }
        swipeToConfirm()
        composeTestRule.waitUntil(5_000) { swipes == 4 }
        composeTestRule.onNodeWithTag("SendConfirmAssetButton").assertHasNoClickAction().performTouchInput { click() }
        composeTestRule.runOnIdle { state.value = state.value.copy(payMethod = SendMethod.LIGHTNING) }
        composeTestRule.onNodeWithTag("SendConfirmAssetButton").assertHasNoClickAction().performTouchInput { click() }
        swipeToConfirm()
        composeTestRule.runOnIdle {
            assertEquals(4, swipes)
            assertEquals(0, fundingSwitches)
        }
    }

    private fun swipeToConfirm() {
        val width = composeTestRule.onRoot().fetchSemanticsNode().size.width
        composeTestRule.onNodeWithTag("GRAB").performTouchInput {
            swipe(start = center, end = Offset(width * 0.85f, center.y))
        }
    }
}
