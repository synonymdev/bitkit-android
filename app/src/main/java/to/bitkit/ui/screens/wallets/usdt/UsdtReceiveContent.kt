package to.bitkit.ui.screens.wallets.usdt

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.GradientCircularProgressIndicator
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.screens.wallets.receive.CopyAddressCard
import to.bitkit.ui.screens.wallets.receive.CopyAddressType
import to.bitkit.ui.screens.wallets.receive.ReceiveQrView
import to.bitkit.ui.theme.AppShapes
import to.bitkit.ui.theme.Colors

@Composable
internal fun ColumnScope.UsdtReceiveContent(
    address: String,
    uri: String,
    error: Int?,
    network: String,
    onEdit: () -> Unit,
    onNetwork: () -> Unit,
) {
    var showDetails by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
        if (uri.isNotEmpty()) {
            if (showDetails) {
                Column(
                    modifier = Modifier.fillMaxWidth().height(minOf(maxWidth, maxHeight))
                        .clip(AppShapes.small).background(Colors.Black)
                        .verticalScroll(rememberScrollState()).padding(32.dp)
                ) {
                    CopyAddressCard(
                        title = network + " " + stringResource(R.string.wallet__activity_address),
                        address = address,
                        type = CopyAddressType.ONCHAIN,
                        onClickEditInvoice = null,
                        testTag = "UsdtReceiveAddress",
                        accentColor = Colors.Usdt,
                    )
                }
            } else {
                ReceiveQrView(
                    uri = uri,
                    copyText = address,
                    qrLogoPainter = painterResource(R.drawable.tether_circle),
                    onClickEditInvoice = onEdit,
                    accentColor = Colors.Usdt,
                    shareContent = uri,
                    modifier = Modifier.fillMaxWidth().height(
                        minOf(maxHeight, maxWidth + ButtonSize.Small.height + 32.dp)
                    )
                )
            }
        } else if (error != null) {
            BodyS(stringResource(error), color = Colors.Brand)
        } else {
            GradientCircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
    }
    SecondaryButton(
        text = stringResource(R.string.usdt__receive_network, network),
        onClick = onNetwork,
        size = ButtonSize.Small,
        icon = {
            Icon(
                painterResource(R.drawable.usdt_network),
                null,
                tint = Colors.Usdt,
                modifier = Modifier.size(16.dp)
            )
        },
        modifier = Modifier.testTag("UsdtReceiveNetwork")
    )
    VerticalSpacer(16.dp)
    PrimaryButton(
        text = stringResource(if (showDetails) R.string.wallet__receive_show_qr else R.string.common__show_details),
        icon = if (showDetails) {
            {
                Icon(
                    painter = painterResource(R.drawable.ic_qr_purple),
                    contentDescription = null,
                    tint = Colors.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        } else {
            null
        },
        onClick = { showDetails = !showDetails },
        modifier = Modifier.testTag("UsdtReceiveDetails")
    )
}
