package to.bitkit.repositories

import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import to.bitkit.utils.Logger
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaykitPaymentRequestDiagnosticsTest {
    private val sut = PaykitPaymentRequestDiagnostics()

    @Before
    fun setUp() {
        Logger.reset()
        ShadowLog.clear()
    }

    @Test
    fun `parse rejection logs a safe reason and redacted counterparty`() {
        val counterparty = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        sut.logParseRejection(counterparty, PaykitPaymentRequest.ParseFailure.UnsupportedAsset)

        val output = paymentRequestDiagnostic()

        assertTrue(output.contains("category='parse' reason='unsupported_asset'"))
        assertTrue(output.contains("counterparty='pubky3r…k8yw5xg'"))
        assertFalse(output.contains(counterparty))
    }

    @Test
    fun `presentation rejection logs a safe reason and invalid counterparty placeholder`() {
        sut.logPresentationRejection(
            "secret",
            IncomingPaykitPaymentRequestFailureReason.NoSupportedEndpoint,
        )

        val output = paymentRequestDiagnostic()

        assertTrue(output.contains("category='resolution' reason='no_supported_endpoint'"))
        assertTrue(output.contains("counterparty='<invalid>'"))
        assertFalse(output.contains("secret"))
    }

    @Test
    fun `presentation failure logs error type without throwable message`() {
        val counterparty = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        val secret = "private payment payload"
        sut.logPresentationFailure(counterparty, IllegalStateException(secret))

        val output = paymentRequestDiagnostic("Failed to resolve incoming Paykit payment request")

        assertTrue(output.contains("category='resolution' errorType='IllegalStateException'"))
        assertTrue(output.contains("counterparty='pubky3r…k8yw5xg'"))
        assertFalse(output.contains(secret))
        assertFalse(output.contains(counterparty))
    }

    private fun paymentRequestDiagnostic(
        message: String = "Rejected incoming Paykit payment request",
    ): String = ShadowLog.getLogsForTag("APP")
        .single { it.msg.contains(message) }
        .msg
}
