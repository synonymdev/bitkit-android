package to.bitkit.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.shared.util.screen
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun MigrationLoadingScreen(isVisible: Boolean = true) {
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.keepScreenOn(),
    ) {
        Content()
    }
}

@Composable
private fun Content() {
    Column(
        modifier = Modifier
            .screen()
            .padding(horizontal = 32.dp)
    ) {
        AppTopBar(
            titleText = stringResource(R.string.migration__nav_title),
            onBackClick = null,
        )

        Image(
            painter = painterResource(R.drawable.wallet),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.0f)
                .weight(1f)
        )

        Display(
            text = stringResource(R.string.migration__title).withAccent(accentColor = Colors.Brand),
            color = Colors.White,
        )

        BodyM(
            text = stringResource(R.string.migration__description),
            color = Colors.White64,
        )

        VerticalSpacer(32.dp)

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(32.dp),
                color = Colors.White32,
                strokeWidth = 3.dp,
            )
        }

        VerticalSpacer(32.dp)
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content()
    }
}
