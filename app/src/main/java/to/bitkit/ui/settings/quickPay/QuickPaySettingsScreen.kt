package to.bitkit.ui.settings.quickPay

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.Display
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors


@Composable
fun QuickPaySettingsScreen(
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    ScreenColumn {

        AppTopBar(
            titleText = stringResource(R.string.settings__quickpay__nav_title),
            onBackClick = onBack,
            actions = { CloseNavIcon(onClick = onClose) },
        )

        Column(
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            //TODO IMPLEMENT THE FEATURE

            Image(
                painter = painterResource(R.drawable.fast_forward),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 60.dp)
                    .weight(1f)
            )

            Display(
                text = "Coming soon",
                color = Colors.White
            )
            Spacer(Modifier.height(8.dp))
            BodyS(text = stringResource(R.string.settings__quickpay__settings__note), color = Colors.White64)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        QuickPaySettingsScreen(
            onBack = {},
            onClose = {},
        )
    }
}
