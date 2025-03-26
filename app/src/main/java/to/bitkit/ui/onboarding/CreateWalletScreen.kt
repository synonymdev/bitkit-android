package to.bitkit.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun CreateWalletScreen(
    onCreateClick: () -> Unit,
    onRestoreClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = Modifier
            .fillMaxSize()
    ) {
        Image(
            painter = painterResource(id = R.drawable.wallet),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .padding(top = 125.dp)
                .fillMaxWidth()
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(264.dp)
                .align(Alignment.BottomCenter),
        ) {
            Display(text = stringResource(R.string.onboarding__slide4_header).withAccent())
            Spacer(modifier = Modifier.height(8.dp))
            BodyM(
                text = stringResource(R.string.onboarding__slide4_text).withAccent(
                    defaultColor = Colors.White64,
                    accentStyle = SpanStyle(fontWeight = FontWeight.Bold, color = Colors.White),
                ),
            )

            Spacer(modifier = Modifier.weight(1f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                PrimaryButton(
                    text = stringResource(R.string.onboarding__new_wallet),
                    onClick = onCreateClick,
                    modifier = Modifier.weight(1f)
                )
                SecondaryButton(
                    text = stringResource(R.string.onboarding__restore),
                    onClick = onRestoreClick,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun CreateWalletScreenPreview() {
    AppThemeSurface {
        CreateWalletScreen(
            onCreateClick = {},
            onRestoreClick = {}
        )
    }
}
