package to.bitkit.ui.screens.widgets.calculator

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
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
import to.bitkit.repositories.WidgetsRepo
import to.bitkit.test.annotations.DeviceIntegration
import to.bitkit.test.annotations.DeviceUiIntegration
import to.bitkit.ui.screens.widgets.calculator.components.CalculatorCard
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.viewmodels.CurrencyViewModel
import java.util.Locale
import javax.inject.Inject
import javax.inject.Named
import kotlin.test.assertEquals

@HiltAndroidTest
@UninstallModules(RepoModule::class)
@RunWith(AndroidJUnit4::class)
@DeviceIntegration
@DeviceUiIntegration
class CalculatorCardIntegrationTest {

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
    private lateinit var currencyViewModel: CurrencyViewModel
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
                    showWidgetTitles = true,
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
        currencyViewModel = createCurrencyViewModel()
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
    fun btcInputUpdatesFiatValueAndPersistsWidgetState() {
        setCalculatorCard()

        replaceInput(BTC_INPUT_INDEX, "12340")

        waitForValues(
            btcValue = "12340",
            fiatValue = "12.34",
        )

        assertInputText(BTC_INPUT_INDEX, "12 340")
        assertInputText(FIAT_INPUT_INDEX, "12.34")
        assertPersistedValues(
            btcValue = "12340",
            fiatValue = "12.34",
        )
    }

    @Test
    fun fiatInputUpdatesBtcValueAndPersistsWidgetState() {
        setCalculatorCard()

        replaceInput(FIAT_INPUT_INDEX, "10.00")

        waitForValues(
            btcValue = "10000",
            fiatValue = "10.00",
        )

        assertInputText(BTC_INPUT_INDEX, "10 000")
        assertInputText(FIAT_INPUT_INDEX, "10.00")
        assertPersistedValues(
            btcValue = "10000",
            fiatValue = "10.00",
        )
    }

    private fun createCalculatorViewModel(): CalculatorViewModel {
        return ViewModelProvider(
            viewModelStore,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return CalculatorViewModel(
                        widgetsRepo = widgetsRepo,
                    ) as T
                }
            },
        )[CalculatorViewModel::class.java]
    }

    private fun createCurrencyViewModel(): CurrencyViewModel = ViewModelProvider(
        viewModelStore,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return CurrencyViewModel(
                    currencyRepo = currencyRepo,
                ) as T
            }
        },
    )[CurrencyViewModel::class.java]

    private fun setCalculatorCard() {
        composeTestRule.setContent {
            AppThemeSurface {
                CalculatorCard(
                    currencyViewModel = currencyViewModel,
                    calculatorViewModel = calculatorViewModel,
                    showWidgetTitle = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        composeTestRule.waitForIdle()
        composeTestRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            composeTestRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == INPUT_COUNT
        }
    }

    private fun inputAt(index: Int) = composeTestRule.onAllNodes(hasSetTextAction())[index]

    private fun replaceInput(
        index: Int,
        text: String,
    ) {
        inputAt(index).performTextClearance()
        inputAt(index).performTextInput(text)
    }

    private fun waitForValues(
        btcValue: String,
        fiatValue: String,
    ) {
        runCatching {
            composeTestRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
                calculatorViewModel.calculatorValues.value.btcValue == btcValue &&
                    calculatorViewModel.calculatorValues.value.fiatValue == fiatValue
            }
        }.onFailure {
            throw AssertionError(
                buildString {
                    append("Expected calculatorValues btcValue='$btcValue', fiatValue='$fiatValue', ")
                    append("but was '${calculatorViewModel.calculatorValues.value}'. Persisted values were ")
                    append("'${widgetsRepo.widgetsDataFlow.value.calculatorValues}'. Semantics tree:\n")
                    append(composeTestRule.onRoot(useUnmergedTree = true).printToString())
                },
                it,
            )
        }

        val expectedValues = CalculatorValues(
            btcValue = btcValue,
            fiatValue = fiatValue,
        )
        runCatching {
            composeTestRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
                widgetsRepo.widgetsDataFlow.value.calculatorValues == expectedValues
            }
        }.onFailure {
            throw AssertionError(
                "Expected persisted values '$expectedValues', but was " +
                    "'${widgetsRepo.widgetsDataFlow.value.calculatorValues}'",
                it,
            )
        }
    }

    private fun assertInputText(
        inputIndex: Int,
        text: String,
    ) {
        inputAt(inputIndex).assertTextContains(text, substring = true)
        composeTestRule.onNode(hasText(text, substring = true), useUnmergedTree = true)
            .assertIsDisplayed()
    }

    private fun assertPersistedValues(
        btcValue: String,
        fiatValue: String,
    ) {
        assertEquals(
            CalculatorValues(
                btcValue = btcValue,
                fiatValue = fiatValue,
            ),
            widgetsRepo.widgetsDataFlow.value.calculatorValues,
        )
    }

    companion object {
        private const val BTC_INPUT_INDEX = 0
        private const val FIAT_INPUT_INDEX = 1
        private const val INPUT_COUNT = 2
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
        fun provideEnablePolling(): Boolean = false
    }
}
