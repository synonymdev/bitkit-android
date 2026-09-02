package to.bitkit.repositories

import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaykitPaymentRequestDiagnosticsTest {
    private val sut = PaykitPaymentRequestDiagnostics()

    @Before
    fun setUp() {
        ShadowLog.clear()
    }

    @Test
    fun `parse rejection logs a safe reason and invalid counterparty placeholder`() {
        sut.logParseRejection("secret", PaykitPaymentRequest.ParseFailure.UnsupportedAsset)

        val output = paymentRequestDiagnostic()

        assertTrue(output.contains("category='parse' reason='unsupported_asset'"))
        assertTrue(output.contains("counterparty='<invalid>'"))
        assertFalse(output.contains("secret"))
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

    private fun paymentRequestDiagnostic(): String = ShadowLog.getLogsForTag("APP")
        .single { it.msg.contains("Rejected incoming Paykit payment request") }
        .msg
}
