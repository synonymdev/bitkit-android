package to.bitkit.ui.screens.scanner

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.PermissionStatus
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.theme.Shapes
import to.bitkit.ui.utils.withAccent

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraPermissionView(
    permissionState: PermissionState,
    deniedContent: @Composable () -> Unit,
    grantedContent: @Composable () -> Unit,
) {
    AnimatedContent(
        targetState = permissionState.status,
        label = "cameraPermissionAnim",
        contentAlignment = Alignment.Center,
        transitionSpec = { fadeIn(tween()).togetherWith(fadeOut(tween())) },
    ) { permissionStatus ->
        when (permissionStatus) {
            is PermissionStatus.Denied -> deniedContent()
            is PermissionStatus.Granted -> grantedContent()
        }
    }
}

@Composable
fun DeniedContent(
    onClickRetry: () -> Unit = {},
    onClickPaste: () -> Unit = {},
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .clip(Shapes.medium)
            .background(Colors.Black)
    ) {
        FillHeight()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp)
        ) {
            Display(
                stringResource(R.string.other__camera_permission_title)
                    .withAccent(accentColor = Colors.Brand),
                color = Colors.White,
            )

            VerticalSpacer(8.dp)

            BodyM(
                stringResource(R.string.other__camera_permission_description),
                color = Colors.White64,
                modifier = Modifier.fillMaxWidth(),
            )

            VerticalSpacer(32.dp)

            PrimaryButton(
                text = stringResource(R.string.other__camera_permission_button),
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_camera),
                        contentDescription = null,
                    )
                },
                onClick = onClickRetry,
            )
        }

        FillHeight()

        PrimaryButton(
            text = stringResource(R.string.other__qr_paste),
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_clipboard_text_simple),
                    contentDescription = null,
                )
            },
            onClick = onClickPaste,
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewDeniedContent() {
    AppThemeSurface {
        DeniedContent()
    }
}
