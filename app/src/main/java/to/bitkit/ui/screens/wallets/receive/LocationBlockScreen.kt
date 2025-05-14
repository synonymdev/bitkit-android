package to.bitkit.ui.screens.wallets.receive

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.Colors

@Composable
fun LocationBlockScreen(
    onBackPressed: () -> Unit,
    navigateAdvancedSetup: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .gradientBackground()
    ) {
        SheetTopBar(stringResource(R.string.wallet__receive_bitcoin), onBack = onBackPressed)

        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .weight(1f),
        ) {
            BodyM(text = stringResource(R.string.lightning__funding__text_blocked_cjit), color = Colors.White64)

            Spacer(modifier = Modifier.weight(1f))

            Image(
                painter = painterResource(R.drawable.globe),
                contentScale = ContentScale.FillWidth,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 60.dp)
            )

            Spacer(modifier = Modifier.weight(1f))

            PrimaryButton(text = stringResource(R.string.onboarding__advanced_setup), onClick = navigateAdvancedSetup)
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    LocationBlockScreen(
        onBackPressed = {},
        navigateAdvancedSetup = {}
    )
}
