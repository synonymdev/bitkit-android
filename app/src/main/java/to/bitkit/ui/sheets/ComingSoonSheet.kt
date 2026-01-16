package to.bitkit.ui.sheets

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BottomSheet
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SheetSize
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

private fun Modifier.sheet() = this then sheetHeight(SheetSize.MEDIUM, isModal = true)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComingSoonSheet(
    onWalletOverviewClick: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BottomSheet(onDismissRequest = onBack) {
        ComingSoonSheetContent(
            onWalletOverviewClick = onWalletOverviewClick,
            onBack = onBack,
            modifier = modifier.sheet()
        )
    }
}

@Composable
fun ComingSoonSheetContent(
    onWalletOverviewClick: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .testTag("ComingSoonSheet")
    ) {
        SheetTopBar(titleText = stringResource(R.string.coming_soon__title), onBack = onBack)
        Column(
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.img_cronometer),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
            Display(
                text = stringResource(R.string.coming_soon__headline).withAccent(accentColor = Colors.Brand),
                color = Colors.White,
            )
            VerticalSpacer(8.dp)
            BodyM(text = stringResource(R.string.coming_soon__description), color = Colors.White64)
            VerticalSpacer(54.dp)
            PrimaryButton(
                text = stringResource(R.string.coming_soon__button),
                onClick = onWalletOverviewClick,
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            ComingSoonSheetContent(
                onWalletOverviewClick = {},
                onBack = {},
                modifier = Modifier.sheet()
            )
        }
    }
}
