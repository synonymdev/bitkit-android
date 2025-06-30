package to.bitkit.ui.screens.wallets.activity

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.Colors

@Composable
fun BoostTransactionSheet(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .gradientBackground()
    ) {
        SheetTopBar(titleText = stringResource(R.string.wallet__boost_title))

        BodyS(text = stringResource(R.string.wallet__boost_fee_recomended), color = Colors.White64)

        Row(
            modifier = Modifier.fillMaxWidth()
        ) {
            Image(
                painter = painterResource(R.drawable.ic_timer_alt_yellow),
                contentDescription = null
            )

            Column(modifier = Modifier.weight(1f)) {
                BodyMSB(text = stringResource(R.string.wallet__boost), color = Colors.White)
                BodyMSB(text = stringResource(R.string.wallet__boost), color = Colors.White64)
            }

            Column(horizontalAlignment = Alignment.End) {
                BodyMSB(text = "4 250", color = Colors.White)
                BodyMSB(text = "0.85", color = Colors.White64)
            }
        }

    }
}
