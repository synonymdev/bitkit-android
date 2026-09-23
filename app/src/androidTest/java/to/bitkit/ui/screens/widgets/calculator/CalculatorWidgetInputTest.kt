package to.bitkit.ui.screens.widgets.calculator

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.lightningdevkit.ldknode.Bolt11Invoice
import to.bitkit.data.AppCacheData
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.WidgetsData
import to.bitkit.data.WidgetsStore
import to.bitkit.di.RepoModule
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.FxRate
import to.bitkit.models.USD
import to.bitkit.models.WidgetType
import to.bitkit.models.WidgetWithPosition
import to.bitkit.models.WidgetsBackupV1
import to.bitkit.models.widget.CalculatorValues
import to.bitkit.repositories.AmountInputHandler
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.QuickPayInvoiceParser
import to.bitkit.repositories.QuickPayPaymentLookup
import to.bitkit.repositories.QuickPayReconcileRow
import to.bitkit.repositories.WidgetsRepo
import to.bitkit.test.annotations.CalculatorWidget
import to.bitkit.test.annotations.DeviceIntegration
import to.bitkit.test.annotations.DeviceUiIntegration
import to.bitkit.ui.screens.widgets.calculator.components.CalculatorCard
import to.bitkit.ui.screens.widgets.calculator.components.CalculatorNumberPadBar
import to.bitkit.ui.theme.AppThemeSurface
import java.util.Locale
import javax.inject.Inject
import javax.inject.Named
import kotlin.test.assertEquals

@HiltAndroidTest
@UninstallModules(RepoModule::class)
@RunWith(AndroidJUnit4::class)
@CalculatorWidget
@DeviceIntegration
@DeviceUiIntegration
class CalculatorWidgetInputTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @get:Rule
    val composeTestRule = createComposeRule()

    @Inject
    lateinit var widgetsRepo: WidgetsRepo

    @Inject
    lateinit var currencyRepo: CurrencyRepo

    @Inject
    lateinit var widgetsStore: WidgetsStore

    @Inject
    lateinit var settingsStore: SettingsStore

    @Inject
    lateinit var cacheStore: CacheStore

    private lateinit var viewModelStore: ViewModelStore
    private lateinit var calculatorViewModel: CalculatorViewModel
    private lateinit var previousWidgetsData: WidgetsData
    private lateinit var previousSettingsData: SettingsData
    private lateinit var previousCacheData: AppCacheData
    private lateinit var previousLocale: Locale

    @Before
    fun setUp() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        hiltRule.inject()
        viewModelStore = ViewModelStore()

        runBlocking {
            previousWidgetsData = widgetsStore.data.first()
            previousSettingsData = settingsStore.data.first()
            previousCacheData = cacheStore.data.first()

            settingsStore.update {
                it.copy(
                    selectedCurrency = USD,
                    displayUnit = BitcoinDisplayUnit.MODERN,
                )
            }
            cacheStore.update { it.copy(cachedRates = listOf(testUsdRate)) }
            widgetsStore.restoreFromBackup(
                WidgetsBackupV1(
                    createdAt = TEST_CREATED_AT,
                    widgets = WidgetsData(
                        widgets = listOf(WidgetWithPosition(type = WidgetType.CALCULATOR, position = 0)),
                        calculatorValues = emptyCalculatorValues,
                    ),
                )
            ).getOrThrow()

            currencyRepo.currencyState.first {
                it.selectedCurrency == USD &&
                    it.displayUnit == BitcoinDisplayUnit.MODERN &&
                    it.rates.any { rate -> rate.quote == USD && rate.lastPrice == TEST_USD_RATE }
            }
            widgetsRepo.widgetsDataFlow.first {
                it.widgets == listOf(WidgetWithPosition(type = WidgetType.CALCULATOR, position = 0)) &&
                    it.calculatorValues == emptyCalculatorValues
            }
        }

        calculatorViewModel = createCalculatorViewModel()
    }

    @After
    fun tearDown() {
        if (::viewModelStore.isInitialized) {
            viewModelStore.clear()
        }
        runBlocking {
            widgetsStore.restoreFromBackup(
                WidgetsBackupV1(
                    createdAt = TEST_CREATED_AT,
                    widgets = previousWidgetsData,
                )
            ).getOrThrow()
            settingsStore.update { previousSettingsData }
            cacheStore.update { previousCacheData }
        }
        Locale.setDefault(previousLocale)
    }

    @Test
    fun btcInputViaNumberPadUpdatesFiatAndPersistsWidgetState() {
        setCalculatorWidget()

        composeTestRule.onNodeWithTag(BTC_INPUT_TAG).performClick()
        awaitNumberPad()
        tapKeys("N1", "N2", "N3", "N4", "N0")

        waitForValues(btcValue = "12340", fiatValue = "12.34")
        assertPersistedValues(btcValue = "12340", fiatValue = "12.34")
    }

    @Test
    fun fiatInputViaNumberPadUpdatesBtcAndPersistsWidgetState() {
        setCalculatorWidget()

        composeTestRule.onNodeWithTag(FIAT_INPUT_TAG).performClick()
        awaitNumberPad()
        tapKeys("N1", "N0", "NDecimal", "N0", "N0")

        waitForValues(btcValue = "10000", fiatValue = "10.00")
        assertPersistedValues(btcValue = "10000", fiatValue = "10.00")
    }

    private fun createCalculatorViewModel(): CalculatorViewModel {
        return ViewModelProvider(
            viewModelStore,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return CalculatorViewModel(
                        widgetsRepo = widgetsRepo,
                        currencyRepo = currencyRepo,
                    ) as T
                }
            },
        )[CalculatorViewModel::class.java]
    }

    private fun setCalculatorWidget() {
        composeTestRule.setContent {
            AppThemeSurface {
                val state by calculatorViewModel.uiState.collectAsState()
                Box(modifier = Modifier.fillMaxSize()) {
                    CalculatorCard(
                        btcPrimaryDisplayUnit = state.displayUnit,
                        btcValue = state.btcValue,
                        fiatSymbol = state.currencySymbol,
                        fiatName = state.selectedCurrency,
                        fiatValue = state.fiatValue,
                        activeInput = state.activeInput,
                        onSelectInput = calculatorViewModel::onInputSelected,
                        modifier = Modifier.fillMaxWidth()
                    )
                    state.activeInput?.let { active ->
                        CalculatorNumberPadBar(
                            activeInput = active,
                            btcValue = state.btcValue,
                            fiatValue = state.fiatValue,
                            btcPrimaryDisplayUnit = state.displayUnit,
                            onBtcChange = calculatorViewModel::onBtcInputChanged,
                            onFiatChange = calculatorViewModel::onFiatInputChanged,
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun awaitNumberPad() {
        composeTestRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            composeTestRule.onAllNodesWithTag(NUMBER_PAD_TAG).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun tapKeys(vararg keys: String) {
        keys.forEach { key ->
            composeTestRule.onNodeWithTag(key).performClick()
            composeTestRule.waitForIdle()
        }
    }

    private fun waitForValues(
        btcValue: String,
        fiatValue: String,
    ) {
        composeTestRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            calculatorViewModel.uiState.value.btcValue == btcValue &&
                calculatorViewModel.uiState.value.fiatValue == fiatValue
        }
        val expectedValues = CalculatorValues(
            btcValue = btcValue,
            fiatValue = fiatValue,
            satsValue = btcValue.toLong(),
            displayUnit = BitcoinDisplayUnit.MODERN,
        )
        composeTestRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            widgetsRepo.widgetsDataFlow.value.calculatorValues == expectedValues
        }
    }

    private fun assertPersistedValues(
        btcValue: String,
        fiatValue: String,
    ) {
        assertEquals(
            CalculatorValues(
                btcValue = btcValue,
                fiatValue = fiatValue,
                satsValue = btcValue.toLong(),
                displayUnit = BitcoinDisplayUnit.MODERN,
            ),
            widgetsRepo.widgetsDataFlow.value.calculatorValues,
        )
    }

    companion object {
        private const val BTC_INPUT_TAG = "CalculatorBtcInput"
        private const val FIAT_INPUT_TAG = "CalculatorFiatInput"
        private const val NUMBER_PAD_TAG = "CalculatorNumberPad"
        private const val TIMEOUT_MS = 5_000L
        private const val TEST_CREATED_AT = 0L
        private const val TEST_USD_RATE = "100000"

        private val emptyCalculatorValues = CalculatorValues(
            btcValue = "",
            fiatValue = "",
        )

        private val testUsdRate = FxRate(
            symbol = "BTCUSD",
            lastPrice = TEST_USD_RATE,
            base = "BTC",
            baseName = "Bitcoin",
            quote = USD,
            quoteName = "US Dollar",
            currencySymbol = "$",
            currencyFlag = "US",
            lastUpdatedAt = TEST_CREATED_AT,
        )
    }

    @Module
    @InstallIn(SingletonComponent::class)
    object TestRepoModule {

        @Provides
        fun bindAmountInputHandler(currencyRepo: CurrencyRepo): AmountInputHandler = currencyRepo

        @Provides
        @Named("enablePolling")
        @Suppress("FunctionOnlyReturningConstant")
        fun provideEnablePolling(): Boolean = false

        @Provides
        fun provideQuickPayInvoiceParser(): QuickPayInvoiceParser = QuickPayInvoiceParser { bolt11 ->
            runCatching { Bolt11Invoice.fromStr(bolt11).paymentHash() }.getOrNull()
        }

        @Provides
        fun provideQuickPayPaymentLookup(lightningRepo: LightningRepo): QuickPayPaymentLookup =
            QuickPayPaymentLookup { lightningRepo.listPaymentsOrNull()?.map { QuickPayReconcileRow(it) } }
    }
}
