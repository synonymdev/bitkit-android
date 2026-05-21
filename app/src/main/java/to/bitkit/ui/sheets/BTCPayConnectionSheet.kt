package to.bitkit.ui.sheets

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.models.SamRockPaymentMethod
import to.bitkit.models.SamRockSetupRequest
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.Caption
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.Sheet
import to.bitkit.ui.components.SheetSize
import to.bitkit.ui.components.Title
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.AppViewModel

@Composable
fun BTCPayConnectionSheet(
    sheet: Sheet.BTCPayConnection,
    app: AppViewModel,
) {
    Content(
        setup = sheet.setup,
        onCancel = { app.hideSheet() },
        onConnect = { app.connectBTCPay(sheet.setup) },
    )
}

@Composable
private fun Content(
    setup: SamRockSetupRequest,
    modifier: Modifier = Modifier,
    onCancel: () -> Unit = {},
    onConnect: suspend () -> Result<Unit> = { Result.success(Unit) },
) {
    val scope = rememberCoroutineScope()
    val fallbackError = stringResource(R.string.btcpay__request_error)
    var isConnecting by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .sheetHeight(SheetSize.MEDIUM)
            .gradientBackground()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .testTag("BTCPayConnectionSheet")
    ) {
        SheetTopBar(titleText = stringResource(R.string.btcpay__sheet_title))
        VerticalSpacer(16.dp)

        Column(
            modifier = Modifier.weight(1f)
        ) {
            StoreHeader(setup = setup)
            VerticalSpacer(24.dp)

            BodyM(
                text = stringResource(R.string.btcpay__sheet_description),
                color = Colors.White64,
            )
            VerticalSpacer(20.dp)

            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ConnectionRow(
                    iconRes = R.drawable.ic_btc_circle,
                    title = stringResource(R.string.btcpay__onchain_label),
                    subtitle = stringResource(R.string.btcpay__descriptor_label),
                )

                if (setup.requestsUnsupportedMethods) {
                    ConnectionRow(
                        iconRes = R.drawable.ic_warning,
                        title = stringResource(R.string.btcpay__limited_support_label),
                        subtitle = stringResource(R.string.btcpay__unsupported_note),
                    )
                }
            }

            errorText?.let {
                VerticalSpacer(16.dp)
                BodyS(
                    text = it,
                    color = Colors.Red,
                    modifier = Modifier.testTag("BTCPayConnectionError")
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SecondaryButton(
                text = stringResource(R.string.common__cancel),
                onClick = onCancel,
                fullWidth = false,
                enabled = !isConnecting,
                modifier = Modifier
                    .weight(1f)
                    .testTag("BTCPayConnectionCancel")
            )
            PrimaryButton(
                text = stringResource(R.string.common__connect),
                onClick = {
                    scope.launch {
                        isConnecting = true
                        errorText = null
                        onConnect()
                            .onFailure { errorText = it.message ?: fallbackError }
                        isConnecting = false
                    }
                },
                isLoading = isConnecting,
                fullWidth = false,
                modifier = Modifier
                    .weight(1f)
                    .testTag("BTCPayConnectionConnect")
            )
        }
        VerticalSpacer(16.dp)
    }
}

@Composable
private fun StoreHeader(
    setup: SamRockSetupRequest,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth()
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(Colors.White08)
        ) {
            Image(
                painter = painterResource(R.drawable.ic_store_front),
                contentDescription = null,
                modifier = Modifier.size(56.dp)
            )
        }
        HorizontalSpacer(16.dp)
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Caption(
                text = stringResource(R.string.btcpay__store_label),
                color = Colors.White64,
            )
            VerticalSpacer(6.dp)
            Title(
                text = setup.hostDisplayName,
                color = Colors.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ConnectionRow(
    iconRes: Int,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Colors.White08)
            .padding(16.dp)
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(24.dp)
        )
        HorizontalSpacer(12.dp)
        Column(
            modifier = Modifier.weight(1f)
        ) {
            BodyMSB(
                text = title,
                color = Colors.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            VerticalSpacer(4.dp)
            BodyS(
                text = subtitle,
                color = Colors.White64,
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(
                setup = SamRockSetupRequest(
                    postUrl = "https://example.com/plugins/store/samrock/protocol?setup=btc-chain&otp=abc",
                    storeId = "store",
                    otp = "abc",
                    requestedMethods = setOf(SamRockPaymentMethod.BTC_ONCHAIN, SamRockPaymentMethod.BTC_LIGHTNING),
                    hasUnknownMethods = false,
                    hostDisplayName = "example.com",
                    logDescription = "https://example.com/plugins/store/samrock/protocol",
                )
            )
        }
    }
}
