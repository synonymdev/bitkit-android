@file:OptIn(ExperimentalTime::class)

package to.bitkit.ui.screens.paymentrequests

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.synonym.paykit.PaymentRequestLifecycleState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import to.bitkit.models.PaykitAmount
import to.bitkit.models.PaykitAsset
import to.bitkit.models.PaykitRequestPricing
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.PubkyProfile
import to.bitkit.repositories.CurrencyState
import to.bitkit.repositories.PaykitBillingPeriod
import to.bitkit.repositories.PaykitPaymentRequest
import to.bitkit.repositories.PaykitPaymentRequestDeliveryStatus
import to.bitkit.repositories.PaykitPaymentRequestDirection
import to.bitkit.repositories.PaykitRecurrenceUnit
import to.bitkit.repositories.PaykitSubscription
import to.bitkit.repositories.PaykitSubscriptionMetadata
import to.bitkit.repositories.PaykitSubscriptionRecurrence
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.theme.AppThemeSurface
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@ComposeUi
class PaymentRequestsScreenTest {
    private val originalLocale = Locale.getDefault()

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setup() {
        Locale.setDefault(Locale.US)
    }

    @After
    fun teardown() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun detailsDisablePayWhilePreparationIsPending() {
        val isPreparing = mutableStateOf(false)
        val request = request()
        composeTestRule.setContent {
            PaymentRequestsTestSurface {
                IncomingPaymentRequestDetailsContent(
                    request = request,
                    contact = PubkyProfile.placeholder(request.counterparty),
                    isPending = true,
                    isPreparing = isPreparing.value,
                    onBack = {},
                    onPay = {},
                    onDismiss = { Result.success(Unit) },
                )
            }
        }

        composeTestRule.onNodeWithTag("PaymentRequestDetailsPay").assertIsEnabled()
        composeTestRule.runOnIdle { isPreparing.value = true }
        composeTestRule.onNodeWithTag("PaymentRequestDetailsPay").assertIsNotEnabled()
        composeTestRule.runOnIdle { isPreparing.value = false }
        composeTestRule.onNodeWithTag("PaymentRequestDetailsPay").assertIsEnabled()
    }

    @Test
    fun queueShowsIncomingRequestAndSeeAllAction() {
        val request = request(id = "incoming")

        composeTestRule.setContent {
            PaymentRequestsTestSurface {
                PaymentRequestsSheetContent(
                    requests = persistentListOf(request),
                    contacts = persistentListOf(),
                    subscriptions = persistentListOf(),
                    dismissingRequestIds = persistentSetOf(),
                    onNotNow = {},
                    onSeeAll = {},
                    onPay = {},
                    onDismiss = { Result.success(Unit) },
                    onDetails = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("PaymentRequestRow-incoming").assertIsDisplayed()
        composeTestRule.onNodeWithTag("MoneyPrimary").assertIsDisplayed()
        composeTestRule.onNodeWithTag("MoneySecondary").assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestsSeeAll").assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestDismiss-incoming").assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestPay-incoming").assertIsDisplayed()
        composeTestRule.onNodeWithText("Dismiss").assertIsDisplayed()
    }

    @Test
    fun queueDisablesActionsWhileRequestIsDismissing() {
        val request = request(id = "dismissing")

        composeTestRule.setContent {
            PaymentRequestsTestSurface {
                PaymentRequestsSheetContent(
                    requests = persistentListOf(request),
                    contacts = persistentListOf(),
                    subscriptions = persistentListOf(),
                    dismissingRequestIds = persistentSetOf(request.id),
                    onNotNow = {},
                    onSeeAll = {},
                    onPay = {},
                    onDismiss = { Result.success(Unit) },
                    onDetails = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Dismiss").assertDoesNotExist()
        composeTestRule.onNodeWithText("Pay").assertIsNotEnabled()
    }

    @Test
    fun recurringQueueCardShowsProviderThenSubscriptionName() {
        val contact = PubkyProfile.forDisplay(request().counterparty, "Coffee House", imageUrl = null)
        val subscription = subscription(note = "Weekly coffee")
        val recurringRequest = request(id = subscription.paymentRequestId).copy(
            lifecycleState = PaymentRequestLifecycleState.ACTIVE_RECURRING,
            billingPeriod = PaykitBillingPeriod(
                startsAt = Instant.parse("2027-01-15T08:00:00Z"),
                endsAt = Instant.parse("2027-01-22T08:00:00Z"),
            ),
        )

        composeTestRule.setContent {
            PaymentRequestsTestSurface {
                PaymentRequestsSheetContent(
                    requests = persistentListOf(recurringRequest),
                    contacts = persistentListOf(contact),
                    subscriptions = persistentListOf(subscription),
                    dismissingRequestIds = persistentSetOf(),
                    onNotNow = {},
                    onSeeAll = {},
                    onPay = {},
                    onDismiss = { Result.success(Unit) },
                    onDetails = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Coffee House").assertIsDisplayed()
        composeTestRule.onNodeWithText("Weekly coffee").assertIsDisplayed()
    }

    @Test
    fun historyGroupsCompletedRequestsAndKeepsActiveOutgoingRequests() {
        val now = Clock.System.now()
        val accepted = request(id = "accepted").copy(
            createdAt = now,
            lifecycleState = PaymentRequestLifecycleState.ACCEPTED,
        )
        val outgoing = request(id = "outgoing").copy(
            createdAt = now,
            direction = PaykitPaymentRequestDirection.Outgoing,
            deliveryStatus = PaykitPaymentRequestDeliveryStatus.Sent,
        )

        composeTestRule.setContent {
            PaymentRequestsTestSurface {
                PaymentRequestsContent(
                    requests = persistentListOf(outgoing, accepted),
                    pending = persistentListOf(),
                    contacts = persistentListOf(),
                    subscriptions = persistentListOf(),
                    dismissingRequestIds = persistentSetOf(),
                    onBack = {},
                    onRequestPayment = {},
                    onPay = {},
                    onDismiss = { Result.success(Unit) },
                    onDetails = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("PaymentRequestRow-accepted").assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestRow-outgoing").assertIsDisplayed()
        composeTestRule.onNode(
            hasText("pending") and hasAnyAncestor(hasTestTag("PaymentRequestRow-outgoing"))
        ).assertIsDisplayed()
        composeTestRule.onNode(
            hasText("pending") and hasAnyAncestor(hasTestTag("PaymentRequestRow-accepted"))
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText("PAYMENT REQUESTS").assertIsDisplayed()
        composeTestRule.onNodeWithText("TODAY").assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestCreate").assertIsDisplayed()
    }

    @Test
    fun unpaidHistoryKeepsPrimaryFiatAmountAndShowsLifecycleStatus() {
        val accepted = request(id = "accepted").copy(
            createdAt = Clock.System.now(),
            lifecycleState = PaymentRequestLifecycleState.ACCEPTED,
        )

        composeTestRule.setContent {
            PaymentRequestsTestSurface(CurrencyState(primaryDisplay = PrimaryDisplay.FIAT)) {
                PaymentRequestsContent(
                    requests = persistentListOf(accepted),
                    pending = persistentListOf(),
                    contacts = persistentListOf(),
                    subscriptions = persistentListOf(),
                    dismissingRequestIds = persistentSetOf(),
                    onBack = {},
                    onRequestPayment = {},
                    onPay = {},
                    onDismiss = { Result.success(Unit) },
                    onDetails = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("MoneyPrimary").assertTextContains("$", substring = true)
        composeTestRule.onNode(
            hasText("pending") and hasAnyAncestor(hasTestTag("PaymentRequestRow-accepted"))
        ).assertIsDisplayed()
    }

    @Test
    fun outgoingRequestShowsPendingOnFiatLineWhenBitcoinIsPrimary() {
        setOutgoingRequestContent(PrimaryDisplay.BITCOIN)

        composeTestRule.onNodeWithTag("MoneyPrimary").assertTextContains("₿", substring = true)
        composeTestRule.onNodeWithTag("MoneySecondary").assertTextEquals("pending")
    }

    @Test
    fun outgoingRequestShowsPendingOnFiatLineWhenFiatIsPrimary() {
        setOutgoingRequestContent(PrimaryDisplay.FIAT)

        composeTestRule.onNodeWithTag("MoneyPrimary").assertTextEquals("pending")
        composeTestRule.onNodeWithTag("MoneySecondary").assertTextContains("₿", substring = true)
    }

    @Test
    fun emptyHistoryMatchesPaymentRequestsEmptyState() {
        composeTestRule.setContent {
            PaymentRequestsTestSurface {
                PaymentRequestsContent(
                    requests = persistentListOf(),
                    pending = persistentListOf(),
                    contacts = persistentListOf(),
                    subscriptions = persistentListOf(),
                    dismissingRequestIds = persistentSetOf(),
                    onBack = {},
                    onRequestPayment = {},
                    onPay = {},
                    onDismiss = { Result.success(Unit) },
                    onDetails = {},
                )
            }
        }

        composeTestRule.onNodeWithText("NO PAYMENT\nHISTORY").assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestsEmptyIllustration").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "You have not made any payments to providers and don’t have any payment requests yet."
        ).assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestCreate").assertIsDisplayed()
    }

    @Test
    fun longReceiptStatusKeepsContactAndExactAmountReadable() {
        val status = "Płatność zweryfikowana · 0.001647 USDT"
        val asset = mutableStateOf(PaykitAsset.USDT)
        val fontScale = mutableStateOf(1.3f)
        composeTestRule.setContent {
            PaymentRequestsTestSurface {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value)) {
                    Box(Modifier.width(320.dp).padding(horizontal = 16.dp)) {
                        PaymentRequestCard(
                            request = request().copy(amount = PaykitAmount(asset.value, 1_647u)),
                            contact = PubkyProfile.forDisplay(request().counterparty, "Maria", imageUrl = null),
                            fiatStatus = status,
                        )
                    }
                }
            }
        }
        for (scale in listOf(1.3f, 2f)) {
            composeTestRule.runOnIdle { fontScale.value = scale }
            for (denomination in listOf(PaykitAsset.USDT, PaykitAsset.USD, PaykitAsset.BTC)) {
                composeTestRule.runOnIdle { asset.value = denomination }
                for (text in listOf("Maria", status)) {
                    val results = mutableListOf<TextLayoutResult>()
                    composeTestRule.onNodeWithText(text).assertIsDisplayed()
                        .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
                    val layout = results.single()
                    assertFalse(layout.hasVisualOverflow, "$text must fit without truncation")
                    for (word in text.split(' ')) {
                        val start = text.indexOf(word)
                        assertEquals(layout.getLineForOffset(start), layout.getLineForOffset(start + word.length - 1))
                    }
                }
            }
        }
    }

    @Test
    fun shortWordContactKeepsFullNameBesideLongReceipt() {
        val name = "Ann Lee"
        val status = "Payment verified · 0.001647 USDT"
        val width = mutableStateOf(407.dp)
        composeTestRule.setContent {
            PaymentRequestsTestSurface {
                Box(Modifier.width(width.value).padding(horizontal = 16.dp)) {
                    PaymentRequestCard(
                        request = request().copy(amount = PaykitAmount(PaykitAsset.USDT, 1_647u)),
                        contact = PubkyProfile.forDisplay(request().counterparty, name, imageUrl = null),
                        compactSubtitle = "09:41",
                        fiatStatus = status,
                    )
                }
            }
        }
        for (size in listOf(407.dp, 412.dp)) {
            composeTestRule.runOnIdle { width.value = size }
            for (text in listOf(name, status)) {
                val results = mutableListOf<TextLayoutResult>()
                composeTestRule.onNodeWithText(text).assertIsDisplayed()
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
                assertFalse(results.single().hasVisualOverflow, "$text must fit without truncation at $size")
            }
        }
    }

    @Test
    fun shortAmountLeavesRoomForContactNameAtLargeText() {
        val name = "Alexandra Payer"
        composeTestRule.setContent {
            PaymentRequestsTestSurface {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                    Box(Modifier.width(375.dp).padding(horizontal = 16.dp)) {
                        PaymentRequestCard(
                            request = request().copy(amount = PaykitAmount(PaykitAsset.USD, 200u)),
                            contact = PubkyProfile.forDisplay(request().counterparty, name, imageUrl = null),
                            fiatStatus = "Pending",
                        )
                    }
                }
            }
        }
        for (text in listOf(name, "$2.00", "Pending")) {
            val results = mutableListOf<TextLayoutResult>()
            composeTestRule.onNodeWithText(text).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            assertTrue(results.isNotEmpty())
            assertFalse(results.single().hasVisualOverflow, "$text must fit without truncation")
        }
    }

    @Test
    fun longAmountUsesAvailableCardWidthAtLargeText() {
        val asset = mutableStateOf(PaykitAsset.BTC)
        composeTestRule.setContent {
            PaymentRequestsTestSurface {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                    Box(Modifier.width(320.dp).padding(horizontal = 16.dp)) {
                        PaymentRequestCard(
                            request = request().copy(amount = PaykitAmount(asset.value, 1_000_000u)),
                            contact = PubkyProfile.forDisplay(request().counterparty, "Alice", imageUrl = null),
                        )
                    }
                }
            }
        }
        for ((value, text) in listOf(PaykitAsset.BTC to "₿ 1 000 000", PaykitAsset.USD to "$10,000.00")) {
            composeTestRule.runOnIdle { asset.value = value }
            val results = mutableListOf<TextLayoutResult>()
            composeTestRule.onNodeWithText(text).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            assertTrue(results.isNotEmpty())
            assertFalse(results.single().hasVisualOverflow, "$text must fit without truncation")
            assertTrue(results.single().lineCount == 1)
        }
    }

    @Test
    fun bitcoinReceiptStatusUsesCaptionInBothUnitSettings() {
        val primary = mutableStateOf(PrimaryDisplay.FIAT)
        val status = "Płatność zweryfikowana · 4,12485 USDT"
        composeTestRule.setContent {
            PaymentRequestsTestSurface(CurrencyState(primaryDisplay = primary.value)) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                    Box(Modifier.width(360.dp)) {
                        PaymentRequestCard(
                            request = request().copy(amount = PaykitAmount(PaykitAsset.BTC, 5_000u)),
                            contact = PubkyProfile.forDisplay(request().counterparty, "Alice", imageUrl = null),
                            fiatStatus = status,
                        )
                    }
                }
            }
        }
        val layouts = PrimaryDisplay.entries.map { value ->
            composeTestRule.runOnIdle { primary.value = value }
            val results = mutableListOf<TextLayoutResult>()
            composeTestRule.onNodeWithText(status).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            results.single().also { layout ->
                assertFalse(layout.hasVisualOverflow)
                val wordStart = status.indexOf("zweryfikowana")
                val wordEnd = wordStart + "zweryfikowana".lastIndex
                assertTrue(layout.getLineForOffset(wordStart) == layout.getLineForOffset(wordEnd))
            }
        }
        assertTrue(layouts.first().layoutInput.style.fontSize == layouts.last().layoutInput.style.fontSize)
    }

    private fun setOutgoingRequestContent(primaryDisplay: PrimaryDisplay) {
        val outgoing = request(id = "outgoing").copy(
            createdAt = Clock.System.now(),
            direction = PaykitPaymentRequestDirection.Outgoing,
            deliveryStatus = PaykitPaymentRequestDeliveryStatus.Sent,
        )

        composeTestRule.setContent {
            PaymentRequestsTestSurface(CurrencyState(primaryDisplay = primaryDisplay)) {
                PaymentRequestsContent(
                    requests = persistentListOf(outgoing),
                    pending = persistentListOf(),
                    contacts = persistentListOf(),
                    subscriptions = persistentListOf(),
                    dismissingRequestIds = persistentSetOf(),
                    onBack = {},
                    onRequestPayment = {},
                    onPay = {},
                    onDismiss = { Result.success(Unit) },
                    onDetails = {},
                )
            }
        }
    }

    private fun request(id: String = "request") = PaykitPaymentRequest(
        paymentRequestId = id,
        counterparty = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg",
        amount = PaykitAmount(PaykitAsset.BTC, 25_000uL),
        paymentReference = "reference-$id",
        note = "Dinner",
        createdAt = Instant.parse("2027-01-15T08:00:00Z"),
        expiresAt = null,
        acceptedPaymentEndpointIdentifiers = listOf("btc-lightning-bolt11"),
    )

    private fun subscription(note: String) = PaykitSubscription(
        paymentRequestId = "subscription",
        counterparty = request().counterparty,
        amount = PaykitAmount(PaykitAsset.BTC, 25_000uL),
        paymentReference = "invoice",
        pricing = PaykitRequestPricing(),
        note = note,
        createdAt = Instant.parse("2027-01-15T08:00:00Z"),
        proposalExpiresAt = null,
        recurrence = PaykitSubscriptionRecurrence(
            every = 1,
            unit = PaykitRecurrenceUnit.Week,
            startsAt = Instant.parse("2027-01-15T08:00:00Z"),
            anchor = Instant.parse("2027-01-15T08:00:00Z"),
            endsAt = null,
        ),
        metadata = PaykitSubscriptionMetadata(description = null, benefits = emptyList()),
        acceptedPaymentEndpointIdentifiers = listOf("btc-lightning-bolt11"),
        lifecycleState = PaymentRequestLifecycleState.ACTIVE_RECURRING,
        paidPeriods = emptyList(),
    )
}

@Composable
private fun PaymentRequestsTestSurface(
    currencies: CurrencyState = CurrencyState(),
    content: @Composable () -> Unit,
) {
    AppThemeSurface {
        CompositionLocalProvider(
            LocalInspectionMode provides true,
            LocalCurrencies provides currencies,
        ) {
            content()
        }
    }
}
