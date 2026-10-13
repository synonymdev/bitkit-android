package to.bitkit.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import to.bitkit.R
import to.bitkit.models.PaykitAsset
import to.bitkit.repositories.MethodId
import to.bitkit.ui.theme.Colors

@Composable
fun PaykitAcceptedAssets(
    title: String,
    availableMethods: ImmutableList<String>,
    selectedMethods: ImmutableList<String>,
    testTagPrefix: String,
    onClickAsset: (PaykitAsset) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Caption13Up(text = title, color = Colors.White64)
        VerticalSpacer(8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(PaykitAsset.BTC, PaykitAsset.USD).forEach { asset ->
                val isUsdt = asset == PaykitAsset.USD
                val endpoints = availableMethods.filter { rawValue ->
                    MethodId.fromRawValue(rawValue)?.let { (it == MethodId.UsdtArbitrum) == isUsdt } == true
                }
                if (endpoints.isNotEmpty()) {
                    val selected = endpoints.any { it in selectedMethods }
                    NumberPadActionButton(
                        text = if (isUsdt) "USDT" else stringResource(R.string.settings__general__unit_bitcoin),
                        color = if (!selected) Colors.White32 else if (isUsdt) Colors.Usdt else Colors.Brand,
                        outlined = true,
                        onClick = { onClickAsset(asset) },
                        modifier = Modifier
                            .semantics { this.selected = selected }
                            .testTag("${testTagPrefix}Accept-${if (isUsdt) "usdt" else "bitcoin"}")
                    )
                }
            }
        }
    }
}
