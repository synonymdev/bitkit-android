package to.bitkit.ui.screens.wallets.send

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.lightningdevkit.ldknode.OutPoint
import org.lightningdevkit.ldknode.SpendableUtxo
import to.bitkit.R
import to.bitkit.ext.uniqueUtxoKey
import to.bitkit.models.formatToModernDisplay
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.activityListViewModel
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.FillWidth
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.Subtitle
import to.bitkit.ui.components.TagButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppSwitchDefaults
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

object CoinSelectionTestTags {
    const val SCREEN = "coin_selection_screen"
    const val AUTO_SELECT_ROW = "auto_select_row"
    const val UTXO_ROW_PREFIX = "utxo_row"
    const val CONTINUE_BUTTON = "continue_button"
}

@Composable
fun CoinSelectionScreen(
    requiredAmount: ULong,
    address: String,
    onBack: () -> Unit,
    onContinue: (List<SpendableUtxo>) -> Unit,
    viewModel: CoinSelectionViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val tagsByTxId by viewModel.tagsByTxId.collectAsStateWithLifecycle()

    val activity = activityListViewModel ?: return
    val onchainActivities by activity.onchainActivities.collectAsStateWithLifecycle()

    LaunchedEffect(requiredAmount, onchainActivities) {
        viewModel.setOnchainActivities(onchainActivities.orEmpty())
        viewModel.loadUtxos(requiredAmount, address)
    }

    Content(
        uiState = uiState,
        tagsByTxId = tagsByTxId,
        onBack = onBack,
        onContinue = { onContinue(uiState.selectedUtxos) },
        onClickAuto = { viewModel.onToggleAuto() },
        onClickUtxo = { viewModel.onToggleUtxo(it) },
        onRenderUtxo = { viewModel.loadTagsForUtxo(it) },
    )
}

@Composable
private fun Content(
    uiState: CoinSelectionUiState,
    tagsByTxId: Map<String, List<String>> = emptyMap(),
    onBack: () -> Unit = {},
    onContinue: () -> Unit = {},
    onClickAuto: () -> Unit = {},
    onClickUtxo: (SpendableUtxo) -> Unit = {},
    onRenderUtxo: (String) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .testTag(CoinSelectionTestTags.SCREEN)
    ) {
        SheetTopBar(stringResource(R.string.wallet__selection_title), onBack = onBack)

        LazyColumn(
            contentPadding = PaddingValues(vertical = 16.dp),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            // Auto item
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .clickableAlpha { onClickAuto() }
                        .testTag(CoinSelectionTestTags.AUTO_SELECT_ROW)
                ) {
                    BodyMSB(text = stringResource(R.string.wallet__selection_auto))
                    Switch(
                        checked = uiState.autoSelectCoinsOn,
                        onCheckedChange = null, // handled by parent
                        colors = AppSwitchDefaults.colors,
                    )
                }
                HorizontalDivider()
            }

            // Utxo items
            items(uiState.availableUtxos) { utxo ->
                UtxoRow(
                    utxo = utxo,
                    isSelected = uiState.selectedUtxos.any { it.outpoint == utxo.outpoint },
                    tags = tagsByTxId[utxo.outpoint.txid] ?: emptyList(),
                    onTap = { onClickUtxo(utxo) },
                    onRender = onRenderUtxo,
                )
                HorizontalDivider()
            }
        }

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            // Total Required
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Caption13Up(text = stringResource(R.string.wallet__selection_total_required), color = Colors.White64)
                Subtitle(text = uiState.totalRequiredSat.toLong().formatToModernDisplay())
            }
            HorizontalDivider()

            // Total Selected
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Caption13Up(stringResource(R.string.wallet__selection_total_selected), color = Colors.White64)
                Subtitle(text = uiState.totalSelectedSat.toLong().formatToModernDisplay(), color = Colors.Green)
            }
            VerticalSpacer(16.dp)

            PrimaryButton(
                text = stringResource(R.string.common__continue),
                enabled = uiState.isSelectionValid,
                onClick = onContinue,
                modifier = Modifier.testTag(CoinSelectionTestTags.CONTINUE_BUTTON)
            )
            VerticalSpacer(16.dp)
        }
    }
}

@Composable
private fun UtxoRow(
    utxo: SpendableUtxo,
    isSelected: Boolean,
    tags: List<String>,
    onTap: () -> Unit,
    onRender: (String) -> Unit = {},
) {
    LaunchedEffect(utxo.outpoint.txid) { onRender(utxo.outpoint.txid) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clickableAlpha { onTap() }
            .testTag("${CoinSelectionTestTags.UTXO_ROW_PREFIX}_${utxo.uniqueUtxoKey()}")
    ) {
        Column {
            val amount = utxo.valueSats.toLong()

            val isPreview = LocalInspectionMode.current
            if (isPreview) {
                BodyMSB(text = amount.formatToModernDisplay())
                BodySSB(text = "$ 250.13", color = Colors.White64)
                return@Column
            }

            val currency = currencyViewModel ?: return
            val displayUnit = LocalCurrencies.current.displayUnit

            currency.convert(sats = amount)?.let { converted ->
                val btcValue = converted.bitcoinDisplay(displayUnit).value
                BodyMSB(text = btcValue)
                BodySSB(text = "${converted.symbol} ${converted.formatted}", color = Colors.White64)
            }
        }

        if (tags.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            ) {
                items(tags) { tag ->
                    TagButton(text = tag, onClick = null)
                }
            }
        } else {
            FillWidth()
        }

        Switch(
            checked = isSelected,
            onCheckedChange = null, // handled by parent
            colors = AppSwitchDefaults.colors,
        )
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            uiState = CoinSelectionUiState(
                availableUtxos = listOf(
                    SpendableUtxo(outpoint = OutPoint(txid = "abc123", vout = 0u), valueSats = 50000uL),
                    SpendableUtxo(outpoint = OutPoint(txid = "def456", vout = 1u), valueSats = 25000uL),
                    SpendableUtxo(outpoint = OutPoint(txid = "ghi789", vout = 0u), valueSats = 10000uL)
                ),
                selectedUtxos = listOf(
                    SpendableUtxo(outpoint = OutPoint(txid = "abc123", vout = 0u), valueSats = 50000uL),
                ),
                autoSelectCoinsOn = false,
                totalRequiredSat = 30000uL,
                totalSelectedSat = 50000uL,
                isSelectionValid = true,
            ),
            tagsByTxId = mapOf(
                "abc123" to listOf("coffee", "work"),
                "def456" to listOf("shopping", "groceries", "food"),
            )
        )
    }
}

@Preview
@Composable
private fun Preview2() {
    AppThemeSurface {
        Content(
            uiState = CoinSelectionUiState(
                availableUtxos = emptyList(),
                autoSelectCoinsOn = true,
                totalRequiredSat = 1000uL,
                totalSelectedSat = 0uL,
                isSelectionValid = false
            ),
            tagsByTxId = emptyMap()
        )
    }
}
