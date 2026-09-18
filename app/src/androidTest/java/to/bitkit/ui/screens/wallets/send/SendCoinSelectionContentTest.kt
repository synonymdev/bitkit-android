package to.bitkit.ui.screens.wallets.send

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.lightningdevkit.ldknode.OutPoint
import org.lightningdevkit.ldknode.SpendableUtxo
import to.bitkit.ext.uniqueUtxoKey
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.utils.AppError
import kotlin.test.assertTrue

@ComposeUi
class SendCoinSelectionContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val utxo = SpendableUtxo(outpoint = OutPoint(txid = "abc123", vout = 0u), valueSats = 50_000uL)

    @Test
    fun whenLoadingWithoutUtxos_shouldShowSpinnerOnly() {
        composeTestRule.setContent {
            SendCoinSelectionContent(uiState = CoinSelectionUiState(isLoading = true))
        }

        composeTestRule.onNodeWithTag("CoinSelectionLoading").assertExists()
        composeTestRule.onNodeWithTag("CoinSelectionLoadError").assertDoesNotExist()
        composeTestRule.onNodeWithTag("CoinSelectionRetry").assertDoesNotExist()
    }

    @Test
    fun whenLoadFailsWithoutUtxos_shouldShowErrorWithRetryInsteadOfSpinner() {
        composeTestRule.setContent {
            SendCoinSelectionContent(
                uiState = CoinSelectionUiState(loadError = AppError("Node is not setup"))
            )
        }

        composeTestRule.onNodeWithTag("CoinSelectionLoadError").assertExists()
        composeTestRule.onNodeWithTag("CoinSelectionRetry").assertExists()
        composeTestRule.onNodeWithTag("CoinSelectionLoading").assertDoesNotExist()
    }

    @Test
    fun whenRetryingAfterError_shouldShowErrorWithoutSpinner() {
        composeTestRule.setContent {
            SendCoinSelectionContent(
                uiState = CoinSelectionUiState(
                    isLoading = true,
                    loadError = AppError("Node is not setup"),
                )
            )
        }

        composeTestRule.onNodeWithTag("CoinSelectionLoadError").assertExists()
        composeTestRule.onNodeWithTag("CoinSelectionLoading").assertDoesNotExist()
    }

    @Test
    fun whenRetryClicked_shouldTriggerEvent() {
        var eventTriggered = false
        composeTestRule.setContent {
            SendCoinSelectionContent(
                uiState = CoinSelectionUiState(loadError = AppError("Node is not setup")),
                onRetry = { eventTriggered = true },
            )
        }

        composeTestRule.onNodeWithTag("CoinSelectionRetry").performClick()

        assertTrue(eventTriggered)
    }

    @Test
    fun whenUtxosLoaded_shouldShowListWithoutSpinnerOrError() {
        composeTestRule.setContent {
            SendCoinSelectionContent(
                uiState = CoinSelectionUiState(availableUtxos = persistentListOf(utxo))
            )
        }

        composeTestRule.onNodeWithTag("utxo_row_${utxo.uniqueUtxoKey()}").assertExists()
        composeTestRule.onNodeWithTag("CoinSelectionLoading").assertDoesNotExist()
        composeTestRule.onNodeWithTag("CoinSelectionLoadError").assertDoesNotExist()
    }

    @Test
    fun whenUtxosLoadedWhileReloading_shouldKeepListInsteadOfSpinner() {
        composeTestRule.setContent {
            SendCoinSelectionContent(
                uiState = CoinSelectionUiState(
                    availableUtxos = persistentListOf(utxo),
                    isLoading = true,
                )
            )
        }

        composeTestRule.onNodeWithTag("utxo_row_${utxo.uniqueUtxoKey()}").assertExists()
        composeTestRule.onNodeWithTag("CoinSelectionLoading").assertDoesNotExist()
    }
}
