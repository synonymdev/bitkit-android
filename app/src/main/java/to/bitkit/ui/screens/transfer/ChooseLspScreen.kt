package to.bitkit.ui.screens.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import to.bitkit.R
import to.bitkit.models.THIRD_PARTY_LSPS
import to.bitkit.models.ThirdPartyLsp
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun ChooseLspScreen(
    onManualClick: () -> Unit,
    onScanClick: () -> Unit,
    onBackClick: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Content(
        lsps = THIRD_PARTY_LSPS.toImmutableList(),
        onLspClick = { uriHandler.openUri(it.url) },
        onManualClick = onManualClick,
        onScanClick = onScanClick,
        onBackClick = onBackClick,
    )
}

@Composable
private fun Content(
    lsps: ImmutableList<ThirdPartyLsp>,
    modifier: Modifier = Modifier,
    onLspClick: (ThirdPartyLsp) -> Unit = {},
    onManualClick: () -> Unit = {},
    onScanClick: () -> Unit = {},
    onBackClick: () -> Unit = {},
) {
    ScreenColumn(modifier = modifier) {
        AppTopBar(
            titleText = stringResource(R.string.lightning__funding_advanced__nav_title),
            onBackClick = onBackClick,
            actions = { DrawerNavIcon() },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            VerticalSpacer(16.dp)
            Display(
                text = stringResource(R.string.lightning__choose_lsp__title).withAccent(accentColor = Colors.Purple),
            )
            VerticalSpacer(8.dp)
            BodyM(text = stringResource(R.string.lightning__choose_lsp__text), color = Colors.White64)
            VerticalSpacer(67.dp)
            lsps.forEach { lsp ->
                SettingsButtonRow(
                    title = lsp.name,
                    onClick = { onLspClick(lsp) },
                    modifier = Modifier.testTag("ChooseLspProvider")
                )
            }
            VerticalSpacer(67.dp)
            BodyS(text = stringResource(R.string.lightning__choose_lsp__explanation), color = Colors.White64)
            VerticalSpacer(16.dp)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            SecondaryButton(
                text = stringResource(R.string.lightning__funding_advanced__button2),
                onClick = onManualClick,
                modifier = Modifier
                    .weight(1f)
                    .testTag("ChooseLspManual")
            )
            PrimaryButton(
                text = stringResource(R.string.wallet__receive_show_qr),
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_scan),
                        contentDescription = null,
                        tint = Colors.White,
                        modifier = Modifier.size(16.dp)
                    )
                },
                onClick = onScanClick,
                modifier = Modifier
                    .weight(1f)
                    .testTag("ChooseLspScan")
            )
        }
        VerticalSpacer(16.dp)
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(lsps = THIRD_PARTY_LSPS.toImmutableList())
    }
}
