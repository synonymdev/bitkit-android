package to.bitkit.ui.settings.backups

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun MultipleDevicesScreen(
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    MultipleDevicesContent(
        onContinue = onContinue,
        onBack = onBack,
    )
}

@Composable
private fun MultipleDevicesContent(
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
    ) {
        SheetTopBar(stringResource(R.string.security__mnemonic_multiple_header), onBack = onBack)
        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            BodyM(
                text = stringResource(R.string.security__mnemonic_multiple_text),
                color = Colors.White64,
            )

            Image(
                painter = painterResource(R.drawable.phone),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            PrimaryButton(
                text = stringResource(R.string.common__ok),
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
        MultipleDevicesContent(
            onContinue = {},
            onBack = {},
        )
    }
}
