package to.bitkit.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import to.bitkit.ui.utils.withAccentBoldBright

@Composable
fun InfoScreenContent(
    navTitle: String,
    title: AnnotatedString,
    description: AnnotatedString,
    image: Painter,
    showCloseButton: Boolean = true,
    buttonText: String,
    onButtonClick: () -> Unit,
    testTag: String,
) {
    ScreenColumn {
        AppTopBar(
            titleText = navTitle,
            onBackClick = null,
            actions = {
                if (showCloseButton) {
                    DrawerNavIcon()
                }
            },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag(testTag)
        ) {
            VerticalSpacer(16.dp)
            Display(text = title)
            VerticalSpacer(8.dp)
            BodyM(text = description, color = Colors.White64)

            FillHeight()

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 16.dp)
            ) {
                Image(
                    painter = image,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(256.dp)
                )
            }

            FillHeight()
            PrimaryButton(
                text = buttonText,
                onClick = onButtonClick,
                modifier = Modifier.testTag("$testTag-button")
            )
            VerticalSpacer(16.dp)
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        InfoScreenContent(
            navTitle = stringResource(R.string.lightning__transfer__nav_title),
            title = stringResource(R.string.lightning__savings_interrupted__title).withAccent(),
            description = stringResource(R.string.lightning__savings_interrupted__text).withAccentBoldBright(),
            image = painterResource(R.drawable.check),
            buttonText = stringResource(R.string.common__ok),
            onButtonClick = {},
            testTag = "",
        )
    }
}
