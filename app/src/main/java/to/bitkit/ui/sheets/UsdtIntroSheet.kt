package to.bitkit.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.screens.wallets.usdt.UsdtCoinIllustration
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.theme.AppTextStyles
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun UsdtIntroSheet(onDecline: () -> Unit, onEnable: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.sheetHeight().background(Colors.Gray7).navigationBarsPadding().testTag("UsdtIntro")
    ) {
        SheetTopBar(
            stringResource(R.string.usdt__intro_title),
            titleStyle = AppTextStyles.Subtitle.copy(letterSpacing = 0.sp, lineHeight = 22.sp),
        )
        Column(
            verticalArrangement = Arrangement.Bottom,
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
        ) {
            UsdtCoinIllustration(modifier = Modifier.align(Alignment.CenterHorizontally))
            VerticalSpacer(32.dp)
            Display(
                stringResource(R.string.usdt__intro_headline).withAccent(accentColor = Colors.Usdt),
                letterSpacing = (-2).sp
            )
            VerticalSpacer(8.dp)
            BodyM(
                stringResource(R.string.usdt__intro_description),
                color = Colors.White64,
                letterSpacing = 0.sp,
            )
        }
        VerticalSpacer(32.dp)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            maxItemsInEachRow = if (LocalDensity.current.fontScale > 1.3f) 1 else 2,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            SecondaryButton(
                stringResource(R.string.usdt__intro_decline),
                onClick = onDecline,
                textStyle = AppTextStyles.BodySSB.copy(letterSpacing = 0.sp),
                testTag = "UsdtIntroDecline",
                modifier = Modifier.weight(1f)
            )
            PrimaryButton(
                stringResource(R.string.usdt__enable),
                onClick = onEnable,
                textStyle = AppTextStyles.BodySSB.copy(letterSpacing = 0.sp),
                modifier = Modifier.weight(1f).testTag("UsdtIntroEnable")
            )
        }
        VerticalSpacer(16.dp)
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface { BottomSheetPreview { UsdtIntroSheet({}, {}) } }
}
