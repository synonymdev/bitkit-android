package to.bitkit.ui.sheets

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.settings.SettingsSwitchRow
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun NfcIntroSheet(
    isQuickPayEnabled: Boolean,
    onToggleQuickPay: () -> Unit,
    onSettings: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .sheetHeight(isModal = true)
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .testTag("NfcIntro")
    ) {
        SheetTopBar(stringResource(R.string.settings__nfc__nav_title))

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Image(
                    painter = painterResource(R.drawable.nfc),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth(NFC_IMAGE_WIDTH_FRACTION)
                        .heightIn(max = 280.dp)
                )
            }

            Display(
                text = stringResource(R.string.settings__nfc__intro__title).withAccent(accentColor = Colors.Purple),
                color = Colors.White,
            )
            VerticalSpacer(8.dp)
            BodyM(
                text = stringResource(R.string.settings__nfc__intro__description),
                color = Colors.White64,
            )
            VerticalSpacer(16.dp)
            SettingsSwitchRow(
                title = stringResource(R.string.settings__nfc__intro__quickpay),
                isChecked = isQuickPayEnabled,
                onClick = onToggleQuickPay,
                switchTestTag = "NfcIntroQuickPay",
            )
            VerticalSpacer(16.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SecondaryButton(
                    text = stringResource(R.string.settings__settings),
                    onClick = onSettings,
                    testTag = "NfcIntroSettings",
                    modifier = Modifier.weight(1f),
                )
                PrimaryButton(
                    text = stringResource(R.string.settings__nfc__intro__continue),
                    onClick = onContinue,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("NfcIntroContinue"),
                )
            }
            VerticalSpacer(16.dp)
        }
    }
}

private const val NFC_IMAGE_WIDTH_FRACTION = 0.7f

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            NfcIntroSheet(
                isQuickPayEnabled = true,
                onToggleQuickPay = {},
                onSettings = {},
                onContinue = {},
            )
        }
    }
}
