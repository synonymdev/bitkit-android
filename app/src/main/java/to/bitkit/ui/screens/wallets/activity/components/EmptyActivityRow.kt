package to.bitkit.ui.screens.wallets.activity.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.CaptionB
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun EmptyActivityRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.Companion.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp)
            .clickableAlpha(onClick = onClick)
    ) {
        CircularIcon(
            icon = painterResource(R.drawable.ic_heartbeat),
            iconColor = Colors.Yellow,
            backgroundColor = Colors.Yellow16,
            size = 32.dp,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BodyMSB(text = stringResource(R.string.wallet__activity_no))
            CaptionB(
                text = stringResource(R.string.wallet__activity_no_explain),
                color = Colors.White64
            )
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        EmptyActivityRow(
            onClick = {},
        )
    }
}
