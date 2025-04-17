package to.bitkit.ui.settings.pin

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun PinPromptScreen(
    showLaterButton: Boolean = true,
    onContinue: () -> Unit,
    onLater: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
            .padding(horizontal = 16.dp)
            .navigationBarsPadding()
    ) {
        SheetTopBar(stringResource(R.string.security__pin_security_header))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .aspectRatio(1f)
                    .align(Alignment.CenterHorizontally)
                    .weight(1f)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.shield),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Title and description
            Display(text = stringResource(R.string.security__pin_security_title).withAccent(accentColor = Colors.Green))

            Spacer(modifier = Modifier.height(8.dp))

            BodyM(
                text = stringResource(R.string.security__pin_security_text),
                color = Colors.White64,
            )

            Spacer(modifier = Modifier.height(32.dp))

            Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                if (showLaterButton) {
                    SecondaryButton(
                        text = stringResource(R.string.common__later),
                        onClick = onLater,
                        modifier = Modifier.weight(1f),
                    )

                    Spacer(modifier = Modifier.width(16.dp))
                }

                PrimaryButton(
                    text = stringResource(R.string.security__pin_security_button),
                    onClick = onContinue,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        PinPromptScreen(
            showLaterButton = false,
            onContinue = {},
            onLater = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewWithLater() {
    AppThemeSurface {
        PinPromptScreen(
            showLaterButton = true,
            onContinue = {},
            onLater = {},
        )
    }
}
