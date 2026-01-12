package to.bitkit.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import to.bitkit.ui.utils.withAccentLink

private val horizontalPadding = 32.dp

@Composable
fun TermsOfUseScreen(
    onNavigateToIntro: () -> Unit,
) {
    Scaffold { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                // Scrolling Content
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = horizontalPadding)
                        .verticalScroll(rememberScrollState())
                        .testTag("TOS")
                ) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Display(text = stringResource(R.string.onboarding__tos_header).withAccent())
                    Spacer(modifier = Modifier.height(12.dp))
                    TosContent()
                    Spacer(modifier = Modifier.height(20.dp))
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(70.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, MaterialTheme.colorScheme.background),
                            )
                        )
                )
            }
            // Footer
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            ) {
                TermsText(
                    title = stringResource(R.string.onboarding__tos_checkbox),
                    bodyText = AnnotatedString(stringResource(R.string.onboarding__tos_checkbox_value)),
                    modifier = Modifier
                        .padding(horizontal = horizontalPadding)
                        .testTag("Check1")
                )
                TermsText(
                    title = stringResource(R.string.onboarding__pp_checkbox),
                    bodyText = stringResource(R.string.onboarding__pp_checkbox_value)
                        .withAccentLink(Env.PRIVACY_POLICY_URL),
                    modifier = Modifier
                        .padding(horizontal = horizontalPadding)
                        .testTag("Check2")
                )

                Spacer(modifier = Modifier.height(24.dp))

                PrimaryButton(
                    text = stringResource(R.string.common__continue),
                    onClick = onNavigateToIntro,
                    modifier = Modifier
                        .padding(horizontal = horizontalPadding)
                        .testTag("Continue")
                )
            }
        }
    }
}

@Composable
private fun TermsText(
    title: String,
    bodyText: AnnotatedString,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(modifier)
    ) {
        VerticalSpacer(14.dp)
        BodyMSB(title)
        VerticalSpacer(4.dp)
        BodySSB(text = bodyText, color = Colors.White64)
        VerticalSpacer(14.dp)
        HorizontalDivider()
    }
}

@Preview(showSystemUi = true)
@Composable
private fun TermsPreview() {
    AppThemeSurface {
        TermsOfUseScreen(
            onNavigateToIntro = {}
        )
    }
}
