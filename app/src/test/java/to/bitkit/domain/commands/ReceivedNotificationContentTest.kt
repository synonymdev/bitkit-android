package to.bitkit.domain.commands

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.R
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.models.BITCOIN_SYMBOL
import to.bitkit.models.ConvertedAmount
import to.bitkit.models.PrimaryDisplay
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.test.BaseUnitTest
import java.math.BigDecimal
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class ReceivedNotificationContentTest : BaseUnitTest() {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val currencyRepo = mock<CurrencyRepo>()
    private val settingsStore = mock<SettingsStore>()

    private lateinit var sut: ReceivedNotificationContent

    private val converted = ConvertedAmount(
        value = BigDecimal("30.79"),
        formatted = "30.79",
        symbol = "$",
        currency = "USD",
        flag = "",
        sats = 48_064L,
        locale = Locale.US,
    )

    @Before
    fun setUp() {
        sut = ReceivedNotificationContent(context, currencyRepo, settingsStore)
    }

    @Test
    fun `bitcoin-primary body shows the bitcoin amount followed by fiat`() = test {
        stubSettings(PrimaryDisplay.BITCOIN)
        whenever(currencyRepo.convertSatsToFiat(any(), anyOrNull())).thenReturn(Result.success(converted))

        val result = sut.build(48_064L)

        assertEquals(context.getString(R.string.notification__received__title), result.title)
        val body = result.body
        assertTrue(body.startsWith("Received"), body)
        assertTrue(body.contains("$BITCOIN_SYMBOL 48 064"), body)
        assertTrue(body.contains("$"), body)
        assertTrue(body.indexOf(BITCOIN_SYMBOL) < body.lastIndexOf("$"), body)
    }

    @Test
    fun `fiat-primary body shows fiat followed by the bitcoin amount`() = test {
        stubSettings(PrimaryDisplay.FIAT)
        whenever(currencyRepo.convertSatsToFiat(any(), anyOrNull())).thenReturn(Result.success(converted))

        val result = sut.build(48_064L)

        val body = result.body
        assertTrue(body.contains("$BITCOIN_SYMBOL 48 064"), body)
        assertTrue(body.indexOf("$") < body.indexOf(BITCOIN_SYMBOL), body)
    }

    @Test
    fun `falls back to the bitcoin amount when fiat conversion fails`() = test {
        stubSettings(PrimaryDisplay.BITCOIN)
        whenever(currencyRepo.convertSatsToFiat(any(), anyOrNull()))
            .thenReturn(Result.failure(RuntimeException("no rate")))

        val result = sut.build(48_064L)

        val amount = "$BITCOIN_SYMBOL 48 064"
        val expected = context.getString(R.string.notification__received__body_amount, amount)
        assertEquals(expected, result.body)
    }

    @Test
    fun `hides the amount when notification details are disabled`() = test {
        whenever(settingsStore.data).thenReturn(flowOf(SettingsData(showNotificationDetails = false)))

        val result = sut.build(48_064L)

        assertEquals(context.getString(R.string.notification__received__body_hidden), result.body)
    }

    private fun stubSettings(primaryDisplay: PrimaryDisplay) {
        whenever(settingsStore.data).thenReturn(
            flowOf(SettingsData(showNotificationDetails = true, primaryDisplay = primaryDisplay)),
        )
    }
}
