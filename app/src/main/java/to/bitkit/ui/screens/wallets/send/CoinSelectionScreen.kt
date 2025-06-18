package to.bitkit.ui.screens.wallets.send

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface

@Composable
fun CoinSelectionScreen(
    onBack: () -> Unit,
    onContinue: () -> Unit,
) {
    Content(
        onBack = onBack,
        onContinue = onContinue,
    )
}

@Composable
private fun Content(
    onBack: () -> Unit = {},
    onContinue: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
    ) {
        SheetTopBar(stringResource(R.string.wallet__selection_title), onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            BodyM(text = "Add content here")

            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.height(16.dp))

            PrimaryButton(
                text = stringResource(R.string.common__continue),
                onClick = onContinue,
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        Content()
    }
}
