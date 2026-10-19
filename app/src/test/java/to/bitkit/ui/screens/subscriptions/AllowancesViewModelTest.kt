@file:OptIn(ExperimentalTime::class)

package to.bitkit.ui.screens.subscriptions

import android.content.Context
import com.synonym.paykit.AllowanceLifecycleState
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.models.ConvertedAmount
import to.bitkit.models.PubkyProfile
import to.bitkit.models.USD
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.CurrencyState
import to.bitkit.repositories.PaykitAllowance
import to.bitkit.repositories.PaykitAllowanceEntry
import to.bitkit.repositories.PaykitAllowanceError
import to.bitkit.repositories.PaykitAllowanceLimits
import to.bitkit.repositories.PaykitAllowanceRepo
import to.bitkit.repositories.PaykitPaymentRequest
import to.bitkit.repositories.PaykitPaymentRequestRepo
import to.bitkit.repositories.PaykitPaymentRequestTarget
import to.bitkit.repositories.PubkyRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.ServiceError
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AllowancesViewModelTest : BaseUnitTest() {
    companion object {
        private const val LEO_KEY = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        private const val MIA_KEY = "pubky1rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"

        /** Sats per US dollar in these tests, so 4 990 sats is $4.99. */
        private val SATS_PER_USD = BigDecimal(1_000)
    }

    private val context: Context = mock()
    private val allowanceRepo: PaykitAllowanceRepo = mock()
    private val paymentRequestRepo: PaykitPaymentRequestRepo = mock()
    private val pubkyRepo: PubkyRepo = mock()
    private val currencyRepo: CurrencyRepo = mock()
    private val settingsStore: SettingsStore = mock()
    private val settings = MutableStateFlow(SettingsData())
    private val clock = object : Clock {
        override fun now() = Instant.parse("2027-01-15T08:00:00Z")
    }

    private val entries = MutableStateFlow<List<PaykitAllowanceEntry>>(emptyList())
    private val autoPaidSats = MutableStateFlow<Map<String, ULong>>(emptyMap())
    private val contacts = MutableStateFlow(listOf(profile(LEO_KEY, "Leo"), profile(MIA_KEY, "Mia")))
    private val targets = MutableStateFlow<List<PaykitPaymentRequestTarget>>(emptyList())

    private lateinit var sut: AllowancesViewModel

    @Before
    fun setUp() {
        whenever(allowanceRepo.entries).thenReturn(entries)
        whenever(settingsStore.data).thenReturn(settings)
        whenever(allowanceRepo.autoPaidSats).thenReturn(autoPaidSats)
        whenever(pubkyRepo.contacts).thenReturn(contacts)
        whenever(paymentRequestRepo.eligibleTargets).thenReturn(targets)
        whenever(paymentRequestRepo.paymentRequestHistory)
            .thenReturn(MutableStateFlow(emptyList<PaykitPaymentRequest>()))
        whenever(currencyRepo.currencyState).thenReturn(MutableStateFlow(CurrencyState()))
        whenever(currencyRepo.convertSatsToFiat(any(), anyOrNull())).thenAnswer {
            val sats = it.getArgument<Long>(0)
            ConvertedAmount(
                value = BigDecimal(sats).divide(SATS_PER_USD),
                formatted = "",
                symbol = "$",
                currency = USD,
                flag = "",
                sats = sats,
            )
        }
        whenever(currencyRepo.convertFiatToSats(any<BigDecimal>(), anyOrNull())).thenAnswer {
            it.getArgument<BigDecimal>(0).multiply(SATS_PER_USD).toLong().toULong()
        }
        whenever(context.getString(any())).thenReturn("")
        mapOf(
            R.string.subscriptions__allowance_status_active to "Active",
            R.string.subscriptions__allowance_status_ended to "Ended",
            R.string.subscriptions__allowance_status_waiting to "Waiting for an answer",
            R.string.subscriptions__allowance_per_payment_short to "{amount} a payment",
            R.string.subscriptions__allowance_paid_automatically to "{amount} paid automatically",
            R.string.subscriptions__allowance_up_to to "Up to {amount}",
            R.string.subscriptions__allowance_error_not_linked to "This contact is not connected yet.",
            R.string.common__error to "Error",
        ).forEach { (id, text) -> whenever(context.getString(id)).thenReturn(text) }
        sut = AllowancesViewModel(
            context = context,
            allowanceRepo = allowanceRepo,
            paymentRequestRepo = paymentRequestRepo,
            pubkyRepo = pubkyRepo,
            currencyRepo = currencyRepo,
            settingsStore = settingsStore,
            clock = clock,
        )
    }

    @Test
    fun `limits known only in sats round back to whole dollars`() = test {
        entries.value = listOf(entry(perPaymentSats = 4_990uL, monthlySats = 49_900uL, limits = null))

        val allowance = loadedState().allowances.single()

        assertEquals("Active · $5.00 a payment", allowance.subtitle)
        assertEquals("Up to $5.00", allowance.perPaymentUpTo)
        assertEquals("50.00", allowance.monthlyAmount)
    }

    @Test
    fun `limits picked on this wallet show the chosen dollar stops`() = test {
        val limits = PaykitAllowanceLimits(
            perPaymentUsd = 5,
            monthlyUsd = 50,
            perPaymentSats = 4_900uL,
            monthlySats = 49_000uL,
        )
        entries.value = listOf(entry(perPaymentSats = 4_900uL, monthlySats = 49_000uL, limits = limits))

        val allowance = loadedState().allowances.single()

        assertEquals("Active · $5 a payment", allowance.subtitle)
        assertEquals("50.00", allowance.monthlyAmount)
    }

    @Test
    fun `an ended allowance shows what it paid automatically`() = test {
        entries.value = listOf(entry(state = AllowanceLifecycleState.ENDED))
        autoPaidSats.value = mapOf("entry-1" to 2_000uL)

        val allowance = loadedState().allowances.single()

        assertEquals("Ended · $2.00 paid automatically", allowance.subtitle)
        assertEquals("$2.00", allowance.paidSoFar)
        assertTrue(allowance.isInactive)
    }

    @Test
    fun `contact picker lists only contacts that can receive payment requests`() = test {
        targets.value = listOf(PaykitPaymentRequestTarget(LEO_KEY))

        assertEquals(listOf("Leo"), loadedState().contacts.map { it.name })
    }

    @Test
    fun `saving converts the chosen dollar stops to sats once`() = test {
        val limits = PaykitAllowanceLimits(
            perPaymentUsd = 5,
            monthlyUsd = 100,
            perPaymentSats = 5_000uL,
            monthlySats = 100_000uL,
        )
        whenever(allowanceRepo.propose(LEO_KEY, limits)).thenReturn(Result.success(Unit))
        var saved = false

        sut.propose(profile(LEO_KEY, "Leo"), perPaymentUsd = 5, monthlyUsd = 100) { saved = true }

        verify(allowanceRepo).propose(LEO_KEY, limits)
        assertTrue(saved)
    }

    @Test
    fun `saving without a dollar rate proposes nothing`() = test {
        whenever(currencyRepo.convertFiatToSats(any<BigDecimal>(), anyOrNull()))
            .thenReturn(Result.failure(ServiceError.CurrencyRateUnavailable()))

        sut.propose(profile(LEO_KEY, "Leo"), perPaymentUsd = 5, monthlyUsd = 100) {}

        verify(allowanceRepo, never()).propose(any(), any())
    }

    @Test
    fun `a failed save keeps the sheet open and frees the button`() = test {
        whenever(allowanceRepo.propose(any(), any())).thenReturn(Result.failure(PaykitAllowanceError.ContactNotLinked))
        var saved = false

        sut.propose(profile(LEO_KEY, "Leo"), perPaymentUsd = 5, monthlyUsd = 100) { saved = true }

        assertEquals(false, saved)
        assertEquals(false, loadedState().isWorking)
    }

    @Test
    fun `saving asks for the pin first when payments require it`() = test {
        settings.value = SettingsData(isPinEnabled = true, isPinForPaymentsEnabled = true, isBiometricEnabled = true)
        whenever(allowanceRepo.propose(any(), any())).thenReturn(Result.success(Unit))
        var saved = false

        sut.propose(profile(LEO_KEY, "Leo"), perPaymentUsd = 5, monthlyUsd = 100) { saved = true }

        assertEquals(AllowanceAuthorization(useBiometrics = true), loadedState().authorization)
        verify(allowanceRepo, never()).propose(any(), any())

        sut.onAuthorized()

        verify(allowanceRepo).propose(eq(LEO_KEY), any())
        assertTrue(saved)
        assertNull(loadedState().authorization)
    }

    @Test
    fun `a cancelled check ends nothing`() = test {
        settings.value = SettingsData(isPinEnabled = true, isPinForPaymentsEnabled = true)
        entries.value = listOf(entry())

        sut.end("entry-1") {}
        assertEquals(AllowanceAuthorization(useBiometrics = false), loadedState().authorization)
        sut.onAuthorizationCancelled()

        verify(allowanceRepo, never()).end(any())
        assertNull(loadedState().authorization)
    }

    @Test
    fun `the payee answers and the payer declines without a check`() = test {
        settings.value = SettingsData(isPinEnabled = true, isPinForPaymentsEnabled = true)
        entries.value = listOf(entry(role = PaykitAllowance.Role.ALLOWEE))
        whenever(allowanceRepo.accept(any())).thenReturn(Result.success(Unit))
        whenever(allowanceRepo.reject(any())).thenReturn(Result.success(Unit))

        sut.accept("entry-1") {}
        sut.decline("entry-1") {}

        verify(allowanceRepo).accept("entry-1")
        verify(allowanceRepo).reject("entry-1")
        assertNull(loadedState().authorization)
    }

    @Test
    fun `nothing is asked when payments need no pin`() = test {
        settings.value = SettingsData(isPinEnabled = true, isPinForPaymentsEnabled = false)
        entries.value = listOf(entry())
        whenever(allowanceRepo.end(any())).thenReturn(Result.success(Unit))

        sut.end("entry-1") {}

        verify(allowanceRepo).end("entry-1")
        assertNull(loadedState().authorization)
    }

    private fun TestScope.loadedState(): AllowancesUiState {
        backgroundScope.launch { sut.uiState.collect {} }
        runCurrent()
        return sut.uiState.value.also { assertTrue(it.isLoaded) }
    }

    private fun entry(
        state: AllowanceLifecycleState = AllowanceLifecycleState.ACCEPTED,
        perPaymentSats: ULong = 5_000uL,
        monthlySats: ULong = 50_000uL,
        limits: PaykitAllowanceLimits? = null,
        role: PaykitAllowance.Role = PaykitAllowance.Role.ALLOWER,
    ) = PaykitAllowanceEntry(
        id = "entry-1",
        allowances = listOf(
            PaykitAllowance(
                id = PaykitAllowance.Id(LEO_KEY, "allowance-1"),
                role = role,
                lifecycleState = state,
                isProposedByMe = true,
                perPaymentMaxSats = perPaymentSats,
                monthlyLimitSats = monthlySats,
                monthlyAnchor = null,
            ),
        ),
        limits = limits,
    )

    private fun profile(publicKey: String, name: String) = PubkyProfile.forDisplay(
        publicKey = publicKey,
        name = name,
        imageUrl = null,
    )
}
