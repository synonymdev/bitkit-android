@file:OptIn(ExperimentalTime::class)

package to.bitkit.ui.screens.subscriptions

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.synonym.paykit.AllowanceLifecycleState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import to.bitkit.R
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.env.Env
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.CurrencyState
import to.bitkit.repositories.PaykitAllowance
import to.bitkit.repositories.PaykitAllowanceEntry
import to.bitkit.repositories.PaykitAllowancePause
import to.bitkit.repositories.PaykitAllowanceRepo
import to.bitkit.repositories.PaykitPaymentRequest
import to.bitkit.repositories.PaykitPaymentRequestRepo
import to.bitkit.repositories.PaykitPaymentRequestTarget
import to.bitkit.repositories.PubkyRepo
import to.bitkit.ui.LocalAppViewModel
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.viewmodels.AppViewModel
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toJavaDuration

@Config(application = Application::class, sdk = [34])
@RunWith(RobolectricTestRunner::class)
class AllowancesScreenTest {
    private companion object {
        const val LEO_KEY = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
    }

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val app: AppViewModel = mock()
    private val allowanceRepo: PaykitAllowanceRepo = mock()
    private val paymentRequestRepo: PaykitPaymentRequestRepo = mock()
    private val pubkyRepo: PubkyRepo = mock()
    private val currencyRepo: CurrencyRepo = mock()
    private val settingsStore: SettingsStore = mock()

    private val pause = MutableStateFlow<PaykitAllowancePause?>(PaykitAllowancePause.LEDGER_BEHIND)
    private var isHostShown by mutableStateOf(true)
    private var allowanceClicks = 0
    private lateinit var viewModel: AllowancesViewModel

    @Before
    fun setUp() {
        whenever(app.pinAttemptsRemaining).thenReturn(MutableStateFlow(Env.PIN_ATTEMPTS))
        whenever(app.validatePin(any())).thenReturn(true)
        whenever(allowanceRepo.entries).thenReturn(MutableStateFlow(listOf(entry())))
        whenever(allowanceRepo.autoPaidSats).thenReturn(MutableStateFlow(emptyMap()))
        whenever(allowanceRepo.automaticPaymentPause).thenReturn(pause)
        whenever { allowanceRepo.refresh() }.thenReturn(Result.success(Unit))
        whenever { allowanceRepo.resumeAutomaticPayments() }.thenReturn(Result.success(Unit))
        whenever(pubkyRepo.contacts).thenReturn(MutableStateFlow(emptyList()))
        whenever(paymentRequestRepo.eligibleTargets)
            .thenReturn(MutableStateFlow(emptyList<PaykitPaymentRequestTarget>()))
        whenever(paymentRequestRepo.paymentRequestHistory)
            .thenReturn(MutableStateFlow(emptyList<PaykitPaymentRequest>()))
        whenever(currencyRepo.currencyState).thenReturn(MutableStateFlow(CurrencyState()))
        whenever(settingsStore.data).thenReturn(
            MutableStateFlow(SettingsData(isPinEnabled = true, isPinForPaymentsEnabled = true))
        )
        val context: Context = mock()
        whenever(context.getString(any())).thenReturn("")
        viewModel = AllowancesViewModel(
            context = context,
            allowanceRepo = allowanceRepo,
            paymentRequestRepo = paymentRequestRepo,
            pubkyRepo = pubkyRepo,
            currencyRepo = currencyRepo,
            settingsStore = settingsStore,
            clock = object : Clock {
                override fun now() = Instant.parse("2027-01-15T08:00:00Z")
            },
        )
        composeRule.setContent {
            AppThemeSurface {
                CompositionLocalProvider(LocalAppViewModel provides app) {
                    if (isHostShown) {
                        AllowanceAuthorizationHost(viewModel = viewModel) {
                            AllowancesScreen(
                                topPadding = 0.dp,
                                onClickAdd = {},
                                onClickAllowance = { allowanceClicks++ },
                                viewModel = viewModel,
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `resume on the tab asks for the pin and resumes once it passes`() {
        click("AllowancesResume")

        composeRule.onNodeWithTag("N1").assertExists()
        verifyBlocking(allowanceRepo, never()) { resumeAutomaticPayments() }

        repeat(Env.PIN_LENGTH) { click("N1") }

        composeRule.onNodeWithTag("N1").assertDoesNotExist()
        verifyBlocking(allowanceRepo) { resumeAutomaticPayments() }
    }

    @Test
    fun `back on the pin leaves payments paused and resume asks again`() {
        click("AllowancesResume")
        composeRule.onNodeWithTag("N1").assertExists()

        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }

        composeRule.onNodeWithTag("N1").assertDoesNotExist()
        verifyBlocking(allowanceRepo, never()) { resumeAutomaticPayments() }
        click("AllowancesResume")
        composeRule.onNodeWithTag("N1").assertExists()
    }

    @Test
    fun `leaving the screen drops a pending pin check`() {
        click("AllowancesResume")
        composeRule.onNodeWithTag("N1").assertExists()

        composeRule.runOnIdle { isHostShown = false }
        composeRule.runOnIdle { isHostShown = true }

        composeRule.onNodeWithTag("N1").assertDoesNotExist()
        verifyBlocking(allowanceRepo, never()) { resumeAutomaticPayments() }
        click("AllowancesResume")
        composeRule.onNodeWithTag("N1").assertExists()
    }

    @Test
    fun `the pause explains a wallet restore and a ledger behind with their own descriptions`() {
        val restored = composeRule.activity.getString(R.string.subscriptions__allowances_paused_restore_description)
        val ledgerBehind = composeRule.activity.getString(R.string.subscriptions__allowances_paused_description)
        composeRule.onNodeWithText(ledgerBehind).assertExists()
        composeRule.onNodeWithText(restored).assertDoesNotExist()

        pause.value = PaykitAllowancePause.RESTORED

        composeRule.onNodeWithText(restored).assertExists()
        composeRule.onNodeWithText(ledgerBehind).assertDoesNotExist()
        composeRule.onNodeWithTag("AllowancesResume").assertExists()

        pause.value = null

        composeRule.onNodeWithTag("AllowancesPaused").assertDoesNotExist()
    }

    // Tall enough that the row lies under the empty part of the PIN check, not under its keypad.
    @Config(qualifiers = "w360dp-h800dp")
    @Test
    fun `a tap on a row under the pin check does not open the allowance`() {
        click("AllowancesResume")
        composeRule.onNodeWithTag("N1").assertExists()

        ShadowSystemClock.advanceBy(1.seconds.toJavaDuration())
        composeRule.onNode(
            SemanticsMatcher("row") {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("AllowanceRow-") == true
            }
        ).performClick()

        composeRule.runOnIdle {
            assertEquals(0, allowanceClicks, "a tap on the PIN overlay reached the allowance row beneath it")
        }
        composeRule.onNodeWithTag("N1").assertExists()
    }

    // Buttons drop clicks closer together than their debounce, which a fresh test clock always is.
    private fun click(tag: String) {
        ShadowSystemClock.advanceBy(1.seconds.toJavaDuration())
        composeRule.onNodeWithTag(tag).performClick()
    }

    private fun entry() = PaykitAllowanceEntry(
        id = "entry-1",
        allowances = listOf(
            PaykitAllowance(
                id = PaykitAllowance.Id(LEO_KEY, "allowance-1"),
                role = PaykitAllowance.Role.ALLOWER,
                lifecycleState = AllowanceLifecycleState.ACCEPTED,
                isProposedByMe = true,
                perPaymentMaxSats = 5_000uL,
                monthlyLimitSats = 50_000uL,
                monthlyAnchor = null,
            ),
        ),
        limits = null,
    )
}
