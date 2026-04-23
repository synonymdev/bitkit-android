package to.bitkit.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Devices.NEXUS_5
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.shared.util.screen
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun CreateWalletScreen(
    onCreateClick: () -> Unit,
    onRestoreClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .screen(insets = null)
            .fillMaxSize(),
    ) {
        Spacer(modifier = Modifier.weight(1f))

        Image(
            painter = painterResource(id = R.drawable.wallet),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .sizeIn(maxWidth = 311.dp, maxHeight = 311.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 48.dp),
        ) {
            Display(text = stringResource(R.string.onboarding__slide4_header).withAccent())
            VerticalSpacer(14.dp)
            BodyM(
                text = stringResource(R.string.onboarding__slide4_text).withAccent(
                    defaultColor = Colors.White64,
                    accentStyle = SpanStyle(fontWeight = FontWeight.Bold, color = Colors.White),
                ),
            )
        }

        VerticalSpacer(32.dp)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PrimaryButton(
                text = stringResource(R.string.onboarding__new_wallet),
                onClick = onCreateClick,
                modifier = Modifier
                    .weight(1f)
                    .testTag("NewWallet")
            )
            SecondaryButton(
                text = stringResource(R.string.onboarding__restore),
                onClick = onRestoreClick,
                modifier = Modifier
                    .weight(1f)
                    .testTag("RestoreWallet")
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Preview(showSystemUi = true)
@Composable
private fun CreateWalletScreenPreview() {
    AppThemeSurface {
        CreateWalletScreen(
            onCreateClick = {},
            onRestoreClick = {}
        )
    }
}

@Preview(showSystemUi = true, device = NEXUS_5)
@Composable
private fun PreviewSmall() {
    AppThemeSurface {
        CreateWalletScreen(
            onCreateClick = {},
            onRestoreClick = {}
        )
    }
}
