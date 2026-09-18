package to.bitkit.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.Colors

@Composable
@Suppress("LongParameterList")
fun SheetIntro(
    navTitle: String,
    title: AnnotatedString,
    description: AnnotatedString,
    @DrawableRes image: Int,
    continueText: String,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    continueLoading: Boolean = false,
    cancelText: String? = null,
    onCancel: (() -> Unit)? = null,
    testTag: String = "SheetIntro",
    cancelTestTag: String = "${testTag}Cancel",
    continueTestTag: String = "${testTag}Continue",
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .testTag(testTag)
    ) {
        SheetTopBar(navTitle)

        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Box(
                contentAlignment = Alignment.BottomCenter,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Image(
                    painter = painterResource(image),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .heightIn(max = 320.dp)
                        .testTag("${testTag}Image")
                )
            }

            VerticalSpacer(32.dp)
            Display(
                text = title,
                color = Colors.White,
                modifier = Modifier.testTag("${testTag}Title")
            )
            VerticalSpacer(8.dp)
            BodyM(
                text = description,
                color = Colors.White64,
                modifier = Modifier.testTag("${testTag}Description")
            )
            VerticalSpacer(32.dp)

            SheetIntroButtons(
                continueText = continueText,
                onContinue = onContinue,
                continueLoading = continueLoading,
                cancelText = cancelText,
                onCancel = onCancel,
                testTag = "${testTag}Buttons",
                cancelTestTag = cancelTestTag,
                continueTestTag = continueTestTag,
            )
            VerticalSpacer(16.dp)
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun SheetIntroButtons(
    continueText: String,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    continueLoading: Boolean = false,
    cancelText: String? = null,
    onCancel: (() -> Unit)? = null,
    testTag: String = "SheetIntroButtons",
    cancelTestTag: String = "SheetIntroCancel",
    continueTestTag: String = "SheetIntroContinue",
) {
    if (cancelText == null || onCancel == null) {
        PrimaryButton(
            text = continueText,
            onClick = onContinue,
            isLoading = continueLoading,
            modifier = modifier.testTag(continueTestTag)
        )
        return
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag(testTag)
    ) {
        SecondaryButton(
            text = cancelText,
            fullWidth = false,
            onClick = onCancel,
            modifier = Modifier
                .weight(1f)
                .testTag(cancelTestTag)
        )
        PrimaryButton(
            text = continueText,
            fullWidth = false,
            onClick = onContinue,
            isLoading = continueLoading,
            modifier = Modifier
                .weight(1f)
                .testTag(continueTestTag)
        )
    }
}
