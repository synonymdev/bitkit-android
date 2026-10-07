package to.bitkit.ui.screens.wallets.usdt

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.GradientCircularProgressIndicator
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.TertiaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.screens.wallets.receive.CopyAddressCard
import to.bitkit.ui.screens.wallets.receive.CopyAddressType
import to.bitkit.ui.screens.wallets.receive.ReceiveQrView
import to.bitkit.ui.theme.Colors

@Composable
internal fun ColumnScope.UsdtReceiveContent(
    address: String,
    uri: String,
    error: Int?,
    warning: Int = R.string.usdt__receive_warning,
    details: @Composable () -> Unit = {},
) {
    var showDetails by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        if (uri.isNotEmpty()) {
            if (showDetails) {
                CopyAddressCard(
                    title = stringResource(R.string.usdt__address),
                    address = address,
                    type = CopyAddressType.ONCHAIN,
                    onClickEditInvoice = null,
                    testTag = "UsdtReceiveAddress",
                    accentColor = Colors.Green,
                )
            } else {
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    ReceiveQrView(
                        uri = uri,
                        copyText = address,
                        qrLogoPainter = null,
                        onClickEditInvoice = null,
                        accentColor = Colors.Green,
                        shareContent = uri,
                        modifier = Modifier.fillMaxWidth().height(maxWidth + ButtonSize.Small.height + 32.dp)
                    )
                }
                VerticalSpacer(16.dp)
                BodyS(
                    address,
                    color = Colors.White64,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.testTag("UsdtReceiveAddress")
                )
            }
        } else if (error != null) {
            BodyS(stringResource(error), color = Colors.Brand)
        } else {
            GradientCircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
        VerticalSpacer(16.dp)
        details()
        BodyS(stringResource(warning), color = Colors.White64)
    }
    if (showDetails) {
        PrimaryButton(
            text = stringResource(R.string.wallet__receive_show_qr),
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_qr_purple),
                    contentDescription = null,
                    tint = Colors.White,
                    modifier = Modifier.size(16.dp)
                )
            },
            onClick = { showDetails = false },
            modifier = Modifier.testTag("UsdtReceiveDetails")
        )
    } else {
        TertiaryButton(
            text = stringResource(R.string.common__show_details),
            onClick = { showDetails = true },
            modifier = Modifier.testTag("UsdtReceiveDetails")
        )
    }
}
