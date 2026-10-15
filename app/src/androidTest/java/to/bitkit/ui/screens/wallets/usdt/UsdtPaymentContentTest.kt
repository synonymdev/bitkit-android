package to.bitkit.ui.screens.wallets.usdt

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.synonym.bitkitcore.UsdtBridgeProvider
import com.synonym.bitkitcore.UsdtDestination
import com.synonym.bitkitcore.UsdtQuote
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import to.bitkit.repositories.UsdtWalletState
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface
import java.util.Locale
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@HiltAndroidTest
@ComposeUi
class UsdtPaymentContentTest {
    private val originalLocale = Locale.getDefault()
    private val quote = UsdtQuote(
        id = "bridge",
        recipient = "0x1111111111111111111111111111111111111111",
        destination = UsdtDestination.POLYGON,
        bridgeProvider = UsdtBridgeProvider.ORCHESTRA,
        amount = 3_000_000u,
        receivedAmount = 2_990_000u,
        maximumFee = 1_470_000u,
        expiresAt = 100u,
    )

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @get:Rule
    val composeTestRule = createComposeRule()

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
    fun bridgeAmountsAndNetworksAreInExpandedDetails() {
        composeTestRule.setContent { Content(UsdtSendState(quote = quote), UsdtPage.AMOUNT) }
        val total = composeTestRule.onNodeWithTag("UsdtMaximumTotal")
        val swipe = composeTestRule.onNodeWithTag("UsdtConfirm")
        composeTestRule.onNodeWithText("YOU SEND").assertDoesNotExist()
        composeTestRule.onNodeWithTag("UsdtRecipientAmount").assertDoesNotExist()
        total.assertDoesNotExist()
        swipe.assertIsDisplayed()
        composeTestRule.onNodeWithTag("UsdtReviewDetails").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Arbitrum One").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("UsdtRecipientAmount").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("±2.99").assertIsDisplayed()
        total.assertIsDisplayed()
        composeTestRule.onNodeWithText("4.47").assertIsDisplayed()
        composeTestRule.onNodeWithText("Polygon").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("All fees are paid in USDT. Final fee may be lower.").assertIsDisplayed()
        swipe.assertIsDisplayed()
        SystemClock.sleep(600)
        composeTestRule.onNodeWithTag("UsdtReviewDetails").performScrollTo().performClick()
        total.assertDoesNotExist()
        composeTestRule.onNodeWithTag("UsdtRecipientAmount").assertDoesNotExist()
    }

    @Test
    fun recipientAddressCanBeFullyReviewed() {
        composeTestRule.setContent { Content(UsdtSendState(quote = quote), UsdtPage.AMOUNT) }
        composeTestRule.onNodeWithTag("UsdtReviewDetails").performScrollTo().performClick()
        val address = composeTestRule.onNodeWithTag("UsdtReviewAddress")
        address.performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        address.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().isLineEllipsized(0))
        address.performClick()
        composeTestRule.waitForIdle()
        layouts.clear()
        address.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().lineCount > 1)
        assertFalse(layouts.single().hasVisualOverflow)
        composeTestRule.onNodeWithTag("UsdtConfirm").assertIsDisplayed()
    }

    @Test
    fun closingPaymentDoesNotOpenRecipientEntry() {
        val page = mutableStateOf(UsdtPage.AMOUNT)
        val state = mutableStateOf(UsdtSendState(quote = quote, submitted = true))
        composeTestRule.setContent {
            Content(state.value, page.value) {
                state.value = UsdtSendState()
                page.value = UsdtPage.WALLET
            }
        }
        composeTestRule.onNodeWithText("Details").performClick()
        composeTestRule.onNodeWithTag("UsdtManual").assertDoesNotExist()
        composeTestRule.onNodeWithTag("UsdtPaste").assertDoesNotExist()
    }

    @Composable
    private fun Content(state: UsdtSendState, page: UsdtPage, onDetails: () -> Unit = {}) {
        AppThemeSurface {
            Box(modifier = Modifier.size(width = 360.dp, height = 600.dp)) {
                UsdtPaymentContent(
                    wallet = UsdtWalletState(),
                    state = state,
                    page = page,
                    recipient = quote.recipient,
                    amount = "3",
                    destination = quote.destination,
                    onRecipientChange = {},
                    onAmountChange = {},
                    onRecipientContinue = {},
                    onPageChange = {},
                    onBack = {},
                    onReview = {},
                    onConfirm = {},
                    onDone = {},
                    onDetails = onDetails,
                )
            }
        }
    }
}
