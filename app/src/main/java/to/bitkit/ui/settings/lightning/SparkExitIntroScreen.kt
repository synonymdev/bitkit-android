package to.bitkit.ui.settings.lightning

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import to.bitkit.ui.utils.withAccentBoldBright

/** When to use an emergency exit, what it costs and how long it takes. */
@Composable
fun SparkExitIntroScreen(
    onBack: () -> Unit,
    onContinue: () -> Unit,
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__spark_exit__nav_title),
            onBackClick = onBack,
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            VerticalSpacer(32.dp)
            Display(
                text = stringResource(R.string.lightning__spark_exit__intro_title).withAccent(),
                modifier = Modifier.fillMaxWidth()
            )
            VerticalSpacer(8.dp)
            BodyM(
                text = stringResource(R.string.lightning__spark_exit__intro_text).withAccentBoldBright(),
                color = Colors.White64,
                modifier = Modifier.fillMaxWidth()
            )
            FillHeight()
            Image(
                painter = painterResource(R.drawable.exclamation_mark),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(256.dp)
            )
            FillHeight()
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SecondaryButton(
                    text = stringResource(R.string.common__cancel),
                    onClick = onBack,
                    modifier = Modifier.weight(1f)
                )
                PrimaryButton(
                    text = stringResource(R.string.common__continue),
                    onClick = onContinue,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("SparkExitIntroContinue")
                )
            }
            VerticalSpacer(16.dp)
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        SparkExitIntroScreen(onBack = {}, onContinue = {})
    }
}
