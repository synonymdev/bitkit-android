package to.bitkit.ui.screens.widgets.calculator

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import dagger.Module
import dagger.Provides
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import dagger.hilt.InstallIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import to.bitkit.test.annotations.DeviceUiIntegrationTest
import to.bitkit.test.annotations.DeviceIntegrationTest
import to.bitkit.data.AppCacheData
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.WidgetsData
import to.bitkit.data.WidgetsStore
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.FxRate
import to.bitkit.models.USD
import to.bitkit.models.WidgetType
import to.bitkit.models.WidgetsBackupV1
import to.bitkit.models.WidgetWithPosition
import to.bitkit.models.widget.CalculatorValues
import to.bitkit.di.RepoModule
import to.bitkit.repositories.AmountInputHandler
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.WidgetsRepo
import to.bitkit.ui.screens.widgets.calculator.components.CalculatorCard
import to.bitkit.ui.theme.AppThemeSurface
import java.util.Locale
import javax.inject.Inject
import javax.inject.Named
import kotlin.test.assertEquals

@HiltAndroidTest
@UninstallModules(RepoModule::class)
@DeviceIntegrationTest
@DeviceUiIntegrationTest
class CalculatorCardIntegrationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

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

    private lateinit var viewModel: CalculatorViewModel
    private lateinit var previousWidgetsData: WidgetsData
    private lateinit var previousSettingsData: SettingsData
    private lateinit var previousCacheData: AppCacheData
    private lateinit var previousLocale: Locale

    @Before
    fun setUp() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        hiltRule.inject()

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
            cacheStore.update {
                it.copy(cachedRates = listOf(testUsdRate))
            }
            widgetsStore.restoreFromBackup(
                WidgetsBackupV1(
                    createdAt = TEST_CREATED_AT,
                    widgets = WidgetsData(
                        widgets = listOf(WidgetWithPosition(type = WidgetType.CALCULATOR, position = 0)),
                        calculatorValues = CalculatorValues(btcValue = "", fiatValue = ""),
                    ),
                )
            )
            currencyRepo.currencyState.first {
                it.selectedCurrency == USD &&
                    it.displayUnit == BitcoinDisplayUnit.MODERN &&
                    it.rates.any { rate ->
                        rate.quote == USD && rate.lastPrice == TEST_USD_RATE
                    }
            }
        }

        viewModel = CalculatorViewModel(
            widgetsRepo = widgetsRepo,
            currencyRepo = currencyRepo,
        )
    }

    @After
    fun tearDown() {
        runBlocking {
            widgetsStore.restoreFromBackup(
                WidgetsBackupV1(
                    createdAt = TEST_CREATED_AT,
                    widgets = previousWidgetsData,
                )
            )
            settingsStore.update { previousSettingsData }
            cacheStore.update { previousCacheData }
        }
        Locale.setDefault(previousLocale)
    }

    @Test
    fun btcInputUpdatesFiatValueAndPersistsWidgetState() {
        setCalculatorCard()

        composeTestRule.onNodeWithTag(BTC_INPUT_TAG)
            .performTextClearance()
        composeTestRule.onNodeWithTag(BTC_INPUT_TAG)
            .performTextInput("12345")

        waitForValues(
            btcValue = "12345",
            fiatValue = "12.34",
        )

        composeTestRule.onNodeWithTag(BTC_INPUT_TAG)
            .assertTextContains("12 345")
        composeTestRule.onNodeWithTag(FIAT_INPUT_TAG)
            .assertTextContains("12.34")
        assertPersistedValues(
            btcValue = "12345",
            fiatValue = "12.34",
        )
    }

    @Test
    fun fiatInputUpdatesBtcValueAndPersistsWidgetState() {
        setCalculatorCard()

        composeTestRule.onNodeWithTag(FIAT_INPUT_TAG)
            .performTextClearance()
        composeTestRule.onNodeWithTag(FIAT_INPUT_TAG)
            .performTextInput("10.00")

        waitForValues(
            btcValue = "10000",
            fiatValue = "10.00",
        )

        composeTestRule.onNodeWithTag(BTC_INPUT_TAG)
            .assertTextContains("10 000")
        composeTestRule.onNodeWithTag(FIAT_INPUT_TAG)
            .assertTextContains("10.00")
        assertPersistedValues(
            btcValue = "10000",
            fiatValue = "10.00",
        )
    }

    private fun setCalculatorCard() {
        composeTestRule.setContent {
            AppThemeSurface {
                CalculatorCard(
                    calculatorViewModel = viewModel,
                    showWidgetTitle = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun waitForValues(
        btcValue: String,
        fiatValue: String,
    ) {
        runCatching {
            composeTestRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
                viewModel.uiState.value.btcValue == btcValue &&
                    viewModel.uiState.value.fiatValue == fiatValue
            }
        }.onFailure {
            throw AssertionError(
                buildString {
                    append("Expected uiState btcValue='$btcValue', fiatValue='$fiatValue', ")
                    append("but was '${viewModel.uiState.value}'. Persisted values were ")
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
        private const val BTC_INPUT_TAG = "CalculatorBtcInput"
        private const val FIAT_INPUT_TAG = "CalculatorFiatInput"
        private const val TIMEOUT_MS = 5_000L
        private const val TEST_CREATED_AT = 0L

        private val testUsdRate = FxRate(
            symbol = "BTCUSD",
            lastPrice = TEST_USD_RATE,
            base = "BTC",
            baseName = "Bitcoin",
            quote = USD,
            quoteName = "US Dollar",
            currencySymbol = "$",
            currencyFlag = "🇺🇸",
            lastUpdatedAt = TEST_CREATED_AT,
        )

        private const val TEST_USD_RATE = "100000"
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
