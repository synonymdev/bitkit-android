package to.bitkit.ui.screens.transfer

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
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
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

// TODO: show on first LN suggestion card click
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferIntroScreen(
    onContinueClick: () -> Unit = {},
    onCloseClick: () -> Unit = {},
) {
    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        Image(
            painter = painterResource(id = R.drawable.lightning),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .padding(top = 130.dp)
                .fillMaxWidth()
        )
        TopAppBar(
            title = { },
            actions = { CloseNavIcon(onCloseClick) },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .align(Alignment.BottomCenter)
        ) {
            Display(stringResource(R.string.lightning__transfer_intro__title).withAccent(accentColor = Colors.Purple))
            Spacer(modifier = Modifier.height(8.dp))
            BodyM(stringResource(R.string.lightning__transfer_intro__text), color = Colors.White64)

            Spacer(modifier = Modifier.height(32.dp))
            PrimaryButton(
                text = stringResource(R.string.lightning__transfer_intro__button),
                onClick = onContinueClick,
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Preview(showSystemUi = true, showBackground = true)
@Composable
private fun TransferIntroScreenPreview() {
    AppThemeSurface {
        TransferIntroScreen()
    }
}
