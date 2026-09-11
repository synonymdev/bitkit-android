package to.bitkit.models

import org.junit.Test
import to.bitkit.utils.ServiceError
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CjitQuoteValidatorTest {
    @Test
    fun `rejects fee equal to invoice amount`() {
        val result = CjitQuoteValidator.validate(
            invoiceSat = 10_000u,
            feeSat = 10_000u,
            channelSizeSat = 20_000u,
        )

        assertIs<ServiceError.CjitQuoteInvalid>(result.exceptionOrNull())
    }

    @Test
    fun `rejects fee greater than invoice amount`() {
        val result = CjitQuoteValidator.validate(
            invoiceSat = 10_000u,
            feeSat = 10_001u,
            channelSizeSat = 20_000u,
        )

        assertIs<ServiceError.CjitQuoteInvalid>(result.exceptionOrNull())
    }

    @Test
    fun `rejects net receive amount greater than channel size`() {
        val result = CjitQuoteValidator.validate(
            invoiceSat = 10_000u,
            feeSat = 1_000u,
            channelSizeSat = 8_999u,
        )

        assertIs<ServiceError.CjitQuoteInvalid>(result.exceptionOrNull())
    }

    @Test
    fun `accepts valid quote`() {
        val result = CjitQuoteValidator.validate(
            invoiceSat = 10_000u,
            feeSat = 1_000u,
            channelSizeSat = 9_000u,
        )

        assertTrue(result.isSuccess)
    }
}
