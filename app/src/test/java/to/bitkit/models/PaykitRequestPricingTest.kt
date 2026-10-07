@file:OptIn(kotlin.time.ExperimentalTime::class)

package to.bitkit.models

import com.synonym.paykit.ConversionRate
import com.synonym.paykit.PaymentConversion
import com.synonym.paykit.PaymentConversionQuoteRecord
import com.synonym.paykit.PaymentDeadline
import org.junit.Test
import to.bitkit.repositories.MethodId
import to.bitkit.repositories.PaykitBillingPeriod
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class PaykitRequestPricingTest {
    private val now = Instant.fromEpochSeconds(1_800_000_000)

    @Test fun `fixed rates determine exact amounts without market rates`() {
        val pricing = PaykitRequestPricing(
            PaymentConversion.Fixed(
                listOf(
                    ConversionRate("btc", "0.000012345"),
                    ConversionRate("usdt", "1"),
                )
            )
        )
        val requested = PaykitAmount.parse(PaykitAsset.USD, "0.05")
        assertEquals(62uL, pricing.payment(requested, PaykitAsset.BTC, null, now).amount.atomic)
        assertEquals(50_000uL, pricing.payment(requested, PaykitAsset.USDT, null, now).amount.atomic)
        assertEquals(requested, pricing.payment(requested, PaykitAsset.USD, null, now).amount)
        assertFails { pricing.payment(requested, PaykitAsset.USDT, null, now, "unknown") }
    }

    @Test fun `subscriptions keep one payment currency across billing periods`() {
        val bitcoin = MethodId.P2wpkh.rawValue
        val lightning = MethodId.Bolt11.rawValue
        val usdt = MethodId.UsdtArbitrum.rawValue
        val available = listOf(bitcoin, lightning, usdt)
        for ((asset, endpoints, paidAsset) in listOf(
            Triple(PaykitAsset.BTC, listOf(bitcoin, lightning), PaykitAsset.BTC),
            Triple(PaykitAsset.USD, listOf(usdt), PaykitAsset.USDT),
        )) {
            val selected = PaykitRequestPricing.subscriptionEndpoints(asset, available)
            assertEquals(endpoints, selected)
            assertTrue(PaykitRequestPricing.subscriptionEndpoints(asset, available - endpoints.toSet()).isEmpty())
            val rates = PaykitRequestPricing.rates(asset, selected, null, now)
            val pricing = PaykitRequestPricing(rates.takeIf { it.isNotEmpty() }?.let(PaymentConversion::Fixed))
            val requested = PaykitAmount.parse(asset, "5")
            val period = PaykitBillingPeriod(now + (31 * 86400).seconds, now + (62 * 86400).seconds)
            val payment = pricing.payment(requested, paidAsset, period, period.startsAt)
            assertEquals("5", payment.amount.value)
            assertEquals(null, payment.quoteId)
            assertEquals(null, payment.expiresAt)
            assertTrue(payment.isValid(period.endsAt))
        }
        assertTrue(PaykitRequestPricing.subscriptionEndpoints(PaykitAsset.USDT, available).isEmpty())
    }

    @Test fun `missing terms or rate never implicitly enable conversion`() {
        val requested = PaykitAmount.parse(PaykitAsset.USD, "5")
        for (pricing in listOf(
            PaykitRequestPricing(),
            PaykitRequestPricing(PaymentConversion.Fixed(listOf(ConversionRate("btc", "0.00001"))))
        )) {
            assertFails { pricing.payment(requested, PaykitAsset.USDT, null, now) }
            assertEquals(requested, pricing.payment(requested, PaykitAsset.USD, null, now).amount)
        }
    }

    @Test fun `quoted arithmetic rejects overflow and rounds only once`() {
        val requested = PaykitAmount.parse(PaykitAsset.BTC, "0.00000001")
        assertEquals(1235uL, requested.quotedTo(PaykitAsset.USDT, "123456.789012345678").atomic)
        assertFails { PaykitAmount(PaykitAsset.USDT, ULong.MAX_VALUE).quotedTo(PaykitAsset.USDT, "2") }
        for (rate in listOf("0", "-1", "NaN", "1e2", "0.123456789012345678901234567890123456789")) {
            assertFails { requested.quotedTo(PaykitAsset.USDT, rate) }
        }
    }

    @Test fun `rates are required only when issuing bitcoin conversions`() {
        val usdt = MethodId.UsdtArbitrum.rawValue
        assertEquals(
            listOf(ConversionRate("usdt", "1")),
            PaykitRequestPricing.rates(PaykitAsset.USD, listOf(usdt), null, now)
        )
        assertFails { PaykitRequestPricing.rates(PaykitAsset.BTC, listOf(usdt), null, now) }
        val stale = PaykitExchangeRate("100000", now.toEpochMilliseconds() - 601000)
        assertFails { PaykitRequestPricing.rates(PaykitAsset.BTC, listOf(usdt), stale, now) }
    }

    @Test fun `recurring payments keep their chosen quote and inclusive deadline`() {
        val period = PaykitBillingPeriod(now, now + 86400.seconds)
        val first = PaymentConversionQuoteRecord(
            "first",
            period.sdkValue,
            listOf(ConversionRate("usdt", "100000")),
            now.toString(),
            (now + 60.seconds).toString(),
            null
        )
        val later = first.copy(eventId = "later", rates = listOf(ConversionRate("usdt", "110000")))
        val pricing = PaykitRequestPricing(
            PaymentConversion.PerPeriod,
            PaymentDeadline.PeriodStart(30u),
            listOf(first, later)
        )
        val requested = PaykitAmount.parse(PaykitAsset.BTC, "0.00001")
        val selected = pricing.payment(requested, PaykitAsset.USDT, period, now)
        assertEquals("1.1", selected.amount.value)
        assertEquals("later", selected.quoteId)
        val pinned = pricing.payment(requested, PaykitAsset.USDT, period, now, "first")
        assertEquals("1", pinned.amount.value)
        assertTrue(pinned.isValid(now + 30.seconds))
        assertFalse(pinned.isValid(now + 31.seconds))
        assertFalse(pinned.isValid(now - 1.seconds))
        assertEquals(pinned, pricing.payment(requested, PaykitAsset.USDT, period, now + 600.seconds, "first"))
        assertFails { pricing.payment(requested, PaykitAsset.USDT, period, now, "missing") }
        assertFails { pricing.payment(requested, PaykitAsset.USDT, null, now) }
    }
}
