@file:OptIn(ExperimentalTime::class)

package to.bitkit.ui.screens.paymentrequests

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class PaymentRequestExpirationTest {
    private val now = Instant.parse("2027-01-15T08:00:00Z")

    @Test
    fun `expired draft uses the default expiration`() {
        assertEquals(PaymentRequestExpiration.Week, PaymentRequestExpiration.from(now, now))
    }

    @Test
    fun `retained draft restores its closest expiration`() {
        assertEquals(PaymentRequestExpiration.Hour, PaymentRequestExpiration.from(now + 1.hours, now))
        assertEquals(PaymentRequestExpiration.Day, PaymentRequestExpiration.from(now + 1.days, now))
        assertEquals(PaymentRequestExpiration.Month, PaymentRequestExpiration.from(now + 30.days, now))
    }
}
