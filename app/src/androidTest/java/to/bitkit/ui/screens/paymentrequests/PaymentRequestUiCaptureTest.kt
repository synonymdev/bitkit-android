@file:OptIn(ExperimentalTime::class)

package to.bitkit.ui.screens.paymentrequests

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.collections.immutable.persistentSetOf
import org.junit.Rule
import org.junit.Test
import to.bitkit.repositories.AmountInputHandler
import to.bitkit.repositories.PaykitPaymentRequestDeliveryStatus
import to.bitkit.repositories.PaykitPaymentRequestDirection
import to.bitkit.repositories.PaykitPaymentRequestDraft
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.viewmodels.AmountInputViewModel
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime

@ComposeUi
class PaymentRequestUiCaptureTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun captureList() {
        val now = Clock.System.now()
        captureScreen("list") {
            PaymentRequestsContent(
                requests = PaymentRequestFigmaFixtures.listRequests(now),
                pending = PaymentRequestFigmaFixtures.listPending(now),
                contacts = PaymentRequestFigmaFixtures.listContacts,
                rejectingRequestIds = persistentSetOf(),
                canRequestPayment = true,
                onBack = {},
                onRequestPayment = {},
                onPay = {},
                onReject = {},
            )
        }
    }

    @Test
    fun captureRecipient() {
        captureSheet("recipient") { modifier ->
            PaymentRequestRecipientContent(
                targets = PaymentRequestFigmaFixtures.recipientTargets,
                contacts = PaymentRequestFigmaFixtures.recipientContacts,
                isCreating = false,
                onBack = {},
                onEditExpiration = {},
                onPaste = { "" },
                onSend = {},
                modifier = modifier
            )
        }
    }

    @Test
    fun captureRecipientInvoice() {
        captureSheet("recipient-invoice") { modifier ->
            PaymentRequestRecipientContent(
                targets = PaymentRequestFigmaFixtures.recipientInvoiceTargets,
                contacts = PaymentRequestFigmaFixtures.recipientInvoiceContacts,
                isCreating = false,
                onBack = {},
                onEditExpiration = {},
                onPaste = { "" },
                onSend = {},
                showContactsHeader = false,
                modifier = modifier
            )
        }
    }

    @Test
    fun captureDetails() {
        captureSheet("details") { modifier ->
            PaymentRequestDetailsContent(
                amountInputViewModel = AmountInputViewModel(AmountInputHandler.stub()),
                initialDraft = PaykitPaymentRequestDraft(
                    amountSats = 14_500uL,
                    note = "Snacks at conference",
                    expiresAt = Clock.System.now() + 7.days,
                ),
                onBack = {},
                onContinue = {},
                modifier = modifier
            )
        }
    }

    @Test
    fun captureDetailsContact() {
        captureSheet("details-contact") { modifier ->
            PaymentRequestDetailsContent(
                amountInputViewModel = AmountInputViewModel(AmountInputHandler.stub()),
                initialDraft = PaykitPaymentRequestDraft(
                    amountSats = 50_000uL,
                    note = "Steaks & Burgers",
                    expiresAt = Clock.System.now() + 7.days,
                ),
                onBack = {},
                onContinue = {},
                recipient = PaymentRequestFigmaFixtures.john,
                modifier = modifier
            )
        }
    }

    @Test
    fun captureAmountEmpty() {
        setSheetContent { modifier ->
            PaymentRequestDetailsContent(
                amountInputViewModel = AmountInputViewModel(AmountInputHandler.stub()),
                initialDraft = PaykitPaymentRequestDraft(
                    amountSats = 0uL,
                    note = "",
                    expiresAt = Clock.System.now() + 7.days,
                ),
                onBack = {},
                onContinue = {},
                modifier = modifier
            )
        }
        composeTestRule.onNodeWithTag("PaymentRequestEditAmount").performClick()
        composeTestRule.waitForIdle()
        save("amount-empty")
    }

    @Test
    fun captureAmountFilled() {
        setSheetContent { modifier ->
            PaymentRequestDetailsContent(
                amountInputViewModel = AmountInputViewModel(AmountInputHandler.stub()),
                initialDraft = PaykitPaymentRequestDraft(
                    amountSats = 50_000uL,
                    note = "Steaks & Burgers",
                    expiresAt = Clock.System.now() + 7.days,
                ),
                onBack = {},
                onContinue = {},
                modifier = modifier
            )
        }
        composeTestRule.onNodeWithTag("PaymentRequestEditAmount").performClick()
        composeTestRule.waitForIdle()
        save("amount-filled")
    }

    @Test
    fun captureSent() {
        captureSheet("sent") { modifier ->
            PaymentRequestSentContent(
                request = PaymentRequestFigmaFixtures.request(
                    id = "outgoing-anna",
                    counterparty = PaymentRequestFigmaFixtures.anna.publicKey,
                    amountSats = 14_500uL,
                    note = "Snacks at conference",
                    createdAt = Clock.System.now(),
                    direction = PaykitPaymentRequestDirection.Outgoing,
                    deliveryStatus = PaykitPaymentRequestDeliveryStatus.Sent,
                ),
                contact = PaymentRequestFigmaFixtures.anna,
                onDone = {},
                modifier = modifier
            )
        }
    }

    private fun captureScreen(name: String, content: @Composable () -> Unit) {
        composeTestRule.setContent {
            AppThemeSurface {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    content()
                }
            }
        }
        composeTestRule.waitForIdle()
        save(name)
    }

    private fun captureSheet(name: String, content: @Composable (Modifier) -> Unit) {
        setSheetContent(content)
        composeTestRule.waitForIdle()
        save(name)
    }

    private fun setSheetContent(content: @Composable (Modifier) -> Unit) {
        composeTestRule.setContent {
            AppThemeSurface {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    BottomSheetPreview {
                        content(Modifier.sheetHeight())
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun save(name: String) {
        composeTestRule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
            ?: composeTestRule.onRoot().captureToImage().asAndroidBitmap()
        val context = instrumentation.targetContext
        val dirs = listOfNotNull(
            File("/data/local/tmp"),
            context.getExternalFilesDir(null),
            context.filesDir,
        )
        var saved = false
        dirs.forEach { dir ->
            runCatching {
                dir.mkdirs()
                File(dir, "$name-after.png").outputStream().use { out ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
                }
                saved = true
                android.util.Log.e("PR_CAPTURE", "saved ${File(dir, "$name-after.png").absolutePath} w=${bitmap.width} h=${bitmap.height}")
            }
        }
        check(saved) { "failed to save $name-after.png" }
    }
}
