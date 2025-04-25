package to.bitkit.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppShapes
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.theme.ModalSheetTopPadding

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForgotPinSheet(
    onDismiss: () -> Unit,
    onResetClick: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = AppShapes.sheet,
        containerColor = Colors.Black,
        dragHandle = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(color = Colors.Gray6)
            ) {
                BottomSheetDefaults.DragHandle()
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .padding(top = ModalSheetTopPadding)
    ) {
        ForgotPinSheetContent(
            onResetClick = {
                onDismiss()
                onResetClick()
            },
        )
    }
}

@Composable
private fun ForgotPinSheetContent(
    onResetClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .gradientBackground()
            .padding(horizontal = 16.dp)
    ) {
        SheetTopBar(stringResource(R.string.security__pin_forgot_title))
        Spacer(modifier = Modifier.height(16.dp))

        BodyM(
            text = stringResource(R.string.security__pin_forgot_text),
            color = Colors.White64,
        )

        Spacer(modifier = Modifier.weight(1f))
        Image(
            painter = painterResource(R.drawable.restore),
            contentDescription = null,
            modifier = Modifier.width(256.dp)
        )
        Spacer(modifier = Modifier.weight(1f))

        PrimaryButton(
            text = stringResource(R.string.security__pin_forgot_reset),
            onClick = onResetClick,
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        ForgotPinSheetContent(
            onResetClick = {},
        )
    }
}
