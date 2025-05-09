package to.bitkit.ui.screens.wallets.receive

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BalanceHeaderView
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.Title
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun LiquidityFeeScreen(modifier: Modifier = Modifier) {

}

@Composable
private fun LiquidityFeeContent(
    receiveSats: Long,
    networkFeeFormatted: String,
    serviceFeeFormatted: String,
    receiveAmountFormatted: String,
    onLearnMoreClick: () -> Unit,
    onContinueClick: () -> Unit,
    onBackClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .gradientBackground()
    ) {
        SheetTopBar(stringResource(R.string.wallet__receive_bitcoin), onBack = onBackClick)
        Spacer(Modifier.height(24.dp))

        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            BalanceHeaderView(
                sats = receiveSats,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(24.dp))
            BodyM(
                text = stringResource(R.string.wallet__receive_connect_initial)
                    .replace("{networkFee}", networkFeeFormatted)
                    .replace("{serviceFee}", serviceFeeFormatted)
                    .withAccent(
                        defaultColor = Colors.White64,
                        accentStyle = SpanStyle(color = Colors.White, fontWeight = FontWeight.Bold)
                    )
            )
            Spacer(modifier = Modifier.height(32.dp))
            Column {
                Caption13Up(text = stringResource(R.string.wallet__receive_will), color = Colors.White64)
                Spacer(Modifier.height(4.dp))
                Title(text = receiveAmountFormatted)
            }
            Spacer(modifier = Modifier.weight(1f))
            Image(
                painter = painterResource(R.drawable.lightning),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .heightIn(max = 256.dp)
                    .fillMaxWidth()
            )
            Spacer(modifier = Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SecondaryButton(
                    text = stringResource(R.string.common__learn_more),
                    onClick = onLearnMoreClick,
                    modifier = Modifier.weight(1f)
                )
                PrimaryButton(
                    text = stringResource(R.string.common__continue),
                    onClick = onContinueClick,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
