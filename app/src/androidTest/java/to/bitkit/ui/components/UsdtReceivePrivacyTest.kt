package to.bitkit.ui.components

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import to.bitkit.ext.getClipboardText
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.screens.wallets.usdt.UsdtReceiveContent
import to.bitkit.ui.sheets.NewTransactionSheetView
import to.bitkit.ui.theme.AppThemeSurface
import java.util.Locale
import kotlin.test.assertEquals

@HiltAndroidTest
@ComposeUi
class UsdtReceivePrivacyTest {
    private val originalLocale = Locale.getDefault()

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setup() {
        Locale.setDefault(Locale.US)
        hiltRule.inject()
    }

    @After
    fun teardown() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun qrCopyPreservesPaymentUriAndDetailsCopyOnlyAddress() {
        val address = "0x18503bD685aF84E6A7bC289898bAa1C3f5d8527E"
        val uri = "ethereum:0xfd086bc7cd5c481dcc9c85ebe478a1c0b69fcbb9@42161/transfer?address=$address&uint256=3500000"
        lateinit var context: Context
        composeTestRule.setContent {
            context = LocalContext.current
            AppThemeSurface {
                Column {
                    UsdtReceiveContent(address, uri, null, "Arbitrum One", {}, {})
                }
            }
        }
        composeTestRule.onNodeWithTag("ReceiveCopyQR").performClick()
        composeTestRule.runOnIdle { assertEquals(uri, context.getClipboardText()) }
        composeTestRule.onNodeWithTag("UsdtReceiveDetails").performClick()
        composeTestRule.onNodeWithText("Copy").performClick()
        composeTestRule.runOnIdle { assertEquals(address, context.getClipboardText()) }
        composeTestRule.runOnIdle { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeTestRule.onNodeWithTag("ReceiveCopyQR").assertIsDisplayed()
    }

    @Test
    fun incomingPaymentHonorsHiddenBalance() {
        composeTestRule.mainClock.autoAdvance = false
        val hidden = mutableStateOf(true)
        composeTestRule.setContent {
            AppThemeSurface {
                NewTransactionSheetView(
                    details = NewTransactionSheetDetails(
                        type = NewTransactionSheetType.ONCHAIN,
                        direction = NewTransactionSheetDirection.RECEIVED,
                        usdtAmount = 123_456_789uL,
                    ),
                    onCloseClick = {},
                    onDetailClick = {},
                    hideBalance = hidden.value,
                )
            }
        }
        composeTestRule.mainClock.advanceTimeBy(1000)
        composeTestRule.onNodeWithText("123.46").assertDoesNotExist()
        composeTestRule.runOnIdle { hidden.value = false }
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.onNodeWithText("123.46").assertIsDisplayed()
    }
}
