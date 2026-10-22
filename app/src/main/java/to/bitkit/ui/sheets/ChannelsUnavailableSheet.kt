package to.bitkit.ui.sheets

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.models.formatChannelsCloseDate
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.SheetSize
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun ChannelsUnavailableSheet(
    showCloseDate: Boolean,
    onOpenChannel: () -> Unit,
    onDismiss: () -> Unit,
) {
    Content(
        showCloseDate = showCloseDate,
        onOpenChannel = onOpenChannel,
        onDismiss = onDismiss,
    )
}

@Composable
private fun Content(
    showCloseDate: Boolean,
    modifier: Modifier = Modifier,
    onOpenChannel: () -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    val locale = LocalConfiguration.current.locales[0]
    val closeDate = remember(locale) { formatChannelsCloseDate(locale) }

    Column(
        modifier = modifier
            .sheetHeight(
                size = SheetSize.MEDIUM
            )
            .gradientBackground()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .testTag("ChannelsUnavailableSheet")
    ) {
        SheetTopBar(titleText = stringResource(R.string.lightning__channels_unavailable__title))

        BodyM(
            text = stringResource(R.string.lightning__channels_unavailable__text),
            color = Colors.White64,
            modifier = Modifier.fillMaxWidth()
        )

        if (showCloseDate) {
            VerticalSpacer(16.dp)
            BodyM(
                text = stringResource(R.string.lightning__channels_closing_notice, closeDate),
                color = Colors.White64,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ChannelsUnavailableCloseDate")
            )
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Image(
                painter = painterResource(R.drawable.exclamation_mark),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(256.dp)
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            SecondaryButton(
                text = stringResource(R.string.common__cancel),
                onClick = onDismiss,
                modifier = Modifier
                    .weight(1f)
                    .testTag("ChannelsUnavailableButton")
            )
            PrimaryButton(
                text = stringResource(R.string.lightning__channels_unavailable__button),
                onClick = onOpenChannel,
                modifier = Modifier
                    .weight(1f)
                    .testTag("ChannelsUnavailableOpenChannel")
            )
        }

        VerticalSpacer(16.dp)
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(showCloseDate = true)
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewWithoutChannels() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(showCloseDate = false)
        }
    }
}
