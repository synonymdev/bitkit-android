package to.bitkit.ui.screens.wallets.sheets

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.ModalBottomSheetHandle
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppShapes
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.theme.InterFontFamily
import to.bitkit.ui.theme.ModalSheetTopPadding
import to.bitkit.ui.utils.withAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HighBalanceWarningSheet(
    onDismiss: () -> Unit,
    understoodClick: () -> Unit,
    learnMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = AppShapes.sheet,
        containerColor = Colors.Black,
        dragHandle = { ModalBottomSheetHandle() },
        modifier = Modifier
            .fillMaxSize()
            .padding(top = ModalSheetTopPadding)
    ) {
        HighBalanceWarningContent(
            understoodClick = understoodClick,
            learnMoreClick = learnMoreClick,
            modifier = modifier
        )
    }
}

@Composable
fun HighBalanceWarningContent(
    understoodClick: () -> Unit,
    learnMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .gradientBackground()
            .testTag("high_balance_intro_screen")
    ) {
        SheetTopBar(stringResource(R.string.other__high_balance__nav_title))

        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(R.drawable.exclamation_mark),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("high_balance_image")
            )

            Display(
                text = stringResource(R.string.other__high_balance__title).withAccent(accentColor = Colors.Yellow),
                color = Colors.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("high_balance_title")
            )
            VerticalSpacer(8.dp)
            BodyM(
                text =
                    stringResource(R.string.other__high_balance__text).withAccent(
                        defaultColor = Colors.White64,
                        accentStyle = SpanStyle(
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            letterSpacing = 0.4.sp,
                            fontFamily = InterFontFamily,
                            color = Colors.White,
                        )
                    ),
                color = Colors.White64,
                modifier = Modifier
                    .testTag("high_balance_description")
            )
            VerticalSpacer(32.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("buttons_row"),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SecondaryButton(
                    text = stringResource(R.string.other__high_balance__cancel),
                    fullWidth = false,
                    onClick = learnMoreClick,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("learn_more_button"),
                )

                PrimaryButton(
                    text = stringResource(R.string.other__high_balance__continue),
                    fullWidth = false,
                    onClick = understoodClick,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("understood_button"),
                )
            }
            VerticalSpacer(16.dp)
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        HighBalanceWarningContent(
            understoodClick = {},
            learnMoreClick = {},
        )
    }
}
