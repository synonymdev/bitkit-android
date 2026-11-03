package to.bitkit.ui.screens.wallets.usdt

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.synonym.bitkitcore.UsdtDestination
import kotlinx.collections.immutable.ImmutableList
import to.bitkit.R
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.FillWidth
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.Colors

@Composable
internal fun UsdtNetworkPicker(
    destination: UsdtDestination,
    destinations: ImmutableList<UsdtDestination>,
    onSelect: (UsdtDestination) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().background(
            Colors.Gray7
        ).padding(horizontal = 16.dp).testTag("UsdtNetworkPicker")
    ) {
        SheetTopBar(stringResource(R.string.usdt__receive_network_title), onBack = onBack)
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp)
        ) {
            destinations.forEach { network ->
                UsdtNetworkRow(network.label, network == destination, onClick = { onSelect(network) })
            }
            BodyS(
                stringResource(R.string.usdt__send_network_note),
                color = Colors.White64,
                letterSpacing = 0.sp,
                modifier = Modifier.padding(vertical = 16.dp)
            )
            UsdtCoinIllustration(modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
internal fun UsdtNetworkRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth().background(
            Colors.Gray6,
            RoundedCornerShape(16.dp)
        ).clickableAlpha(onClick = onClick).padding(16.dp).testTag("UsdtNetwork-$title")
    ) {
        Image(painterResource(R.drawable.tether_circle), null, modifier = Modifier.size(40.dp))
        BodyMSB(title, letterSpacing = 0.sp)
        FillWidth()
        if (selected) {
            Icon(
                painterResource(R.drawable.ic_check),
                null,
                tint = Colors.Usdt,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
