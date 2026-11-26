package to.bitkit.ui.screens.transfer

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.theme.AppTextStyles
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.theme.TopBarHeight
import to.bitkit.ui.utils.PHONE_MAX_WIDTH
import to.bitkit.ui.utils.TABLET_MIN_WIDTH

/** Shortest window that gets the tablet layout; shorter windows keep the phone layout so the button stays on screen */
private val TABLET_LAYOUT_MIN_HEIGHT = 600.dp

/** Headline size on a tablet, large enough to balance the art beside it */
private val TABLET_TITLE_SIZE = 64.sp

/** Body text style on a tablet, scaled with the headline */
private val TabletBodyStyle = AppTextStyles.BodyM.copy(fontSize = 20.sp, lineHeight = 28.sp)

/**
 * An intro screen's art, headline, text and button. Phones stack the text over the bottom of the art;
 * tablets put the art beside the text in landscape and above it in portrait.
 */
@Composable
fun IntroArtLayout(
    @DrawableRes art: Int,
    title: AnnotatedString,
    text: String,
    buttonText: String,
    buttonTestTag: String,
    onButtonClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isTablet = maxWidth >= TABLET_MIN_WIDTH && maxHeight >= TABLET_LAYOUT_MIN_HEIGHT
        if (isTablet && maxWidth > maxHeight) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = TopBarHeight, start = 32.dp, end = 32.dp, bottom = 32.dp)
            ) {
                IntroArt(
                    art = art,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    Column(
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .heightIn(min = maxHeight)
                    ) {
                        TabletIntroText(title, text, buttonText, buttonTestTag, onButtonClick)
                    }
                }
            }
        } else if (isTablet) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = TopBarHeight, start = 32.dp, end = 32.dp, bottom = 48.dp)
            ) {
                IntroArt(
                    art = art,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(vertical = 32.dp)
                )
                TabletIntroText(title, text, buttonText, buttonTestTag, onButtonClick)
            }
        } else {
            IntroArt(
                art = art,
                modifier = Modifier
                    .padding(top = 130.dp)
                    .fillMaxWidth()
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .align(Alignment.BottomCenter)
            ) {
                Display(title)
                Spacer(modifier = Modifier.height(8.dp))
                BodyM(text, color = Colors.White64)

                Spacer(modifier = Modifier.height(32.dp))
                PrimaryButton(
                    text = buttonText,
                    onClick = onButtonClick,
                    modifier = Modifier.testTag(buttonTestTag)
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun IntroArt(@DrawableRes art: Int, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(id = art),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier
    )
}

@Composable
private fun TabletIntroText(
    title: AnnotatedString,
    text: String,
    buttonText: String,
    buttonTestTag: String,
    onButtonClick: () -> Unit,
) {
    Column(modifier = Modifier.widthIn(max = PHONE_MAX_WIDTH)) {
        Display(title, fontSize = TABLET_TITLE_SIZE)
        Spacer(modifier = Modifier.height(16.dp))
        BodyM(text, color = Colors.White64, style = TabletBodyStyle)
        Spacer(modifier = Modifier.height(40.dp))
        PrimaryButton(
            text = buttonText,
            onClick = onButtonClick,
            modifier = Modifier.testTag(buttonTestTag)
        )
    }
}
