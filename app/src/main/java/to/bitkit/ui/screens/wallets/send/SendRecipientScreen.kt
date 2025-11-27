package to.bitkit.ui.screens.wallets.send

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.RectangleButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.SendEvent

@Composable
fun SendRecipientScreen(
    onEvent: (SendEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val app = appViewModel
    Column(
        modifier = modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
    ) {
        SheetTopBar(titleText = stringResource(R.string.wallet__send_bitcoin))
        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            VerticalSpacer(32.dp)
            Caption13Up(text = stringResource(R.string.wallet__send_to), color = Colors.White64)
            VerticalSpacer(16.dp)

            RectangleButton(
                label = stringResource(R.string.wallet__recipient_contact),
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_users),
                        contentDescription = null,
                        tint = Colors.Brand,
                        modifier = Modifier.size(28.dp),
                    )
                },
                modifier = Modifier.padding(bottom = 4.dp).testTag("RecipientContact")
            ) {
                scope.launch {
                    app?.toast(Exception("Coming soon: Contact"))
                }
            }

            RectangleButton(
                label = stringResource(R.string.wallet__recipient_invoice),
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_clipboard_text),
                        contentDescription = null,
                        tint = Colors.Brand,
                        modifier = Modifier.size(28.dp),
                    )
                },
                modifier = Modifier.padding(bottom = 4.dp).testTag("RecipientInvoice")
            ) {
                onEvent(SendEvent.Paste)
            }

            RectangleButton(
                label = stringResource(R.string.wallet__recipient_manual),
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_pencil_simple),
                        contentDescription = null,
                        tint = Colors.Brand,
                        modifier = Modifier.size(28.dp),
                    )
                },
                modifier = Modifier.padding(bottom = 4.dp).testTag("RecipientManual")
            ) {
                onEvent(SendEvent.EnterManually)
            }

            RectangleButton(
                label = stringResource(R.string.wallet__recipient_scan),
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_scan),
                        contentDescription = null,
                        tint = Colors.Brand,
                        modifier = Modifier.size(28.dp),
                    )
                },
                modifier = Modifier.testTag("RecipientScan")
            ) {
                onEvent(SendEvent.Scan)
            }
            Spacer(modifier = Modifier.weight(1f))

            Image(
                painter = painterResource(R.drawable.coin_stack_logo),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            SendRecipientScreen(
                onEvent = {},
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}
