package to.bitkit.ui.screens.scanner

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.PermissionStatus
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.Title
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withBold

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
    shouldShowRationale: Boolean,
    inSheet: Boolean = false,
    onClickOpenSettings: () -> Unit = {},
    onClickRetry: () -> Unit = {},
    onClickPaste: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .then(if (inSheet) Modifier.gradientBackground() else Modifier.background(Colors.Black))
            .then(if (inSheet) Modifier.navigationBarsPadding() else Modifier.systemBarsPadding())
    ) {
        if (!inSheet) {
            AppTopBar(titleText = null, onBack)
        } else {
            SheetTopBar(titleText = null, onBack = onBack)
        }

        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
        ) {
            VerticalSpacer(16.dp)
            Title(
                text = stringResource(R.string.other__camera_ask_title),
                textAlign = TextAlign.Center,
            )
            VerticalSpacer(8.dp)
            BodyM(
                text = stringResource(R.string.other__camera_ask_msg),
                textAlign = TextAlign.Center,
                color = Colors.White64,
            )
            FillHeight()

            Icon(
                painterResource(R.drawable.ic_exclamation),
                contentDescription = null,
                modifier = Modifier.size(60.dp),
            )
            VerticalSpacer(32.dp)
            BodyM(
                text = stringResource(R.string.other__camera_no_text).withBold(),
                textAlign = TextAlign.Center,
            )
            VerticalSpacer(32.dp)

            if (shouldShowRationale) {
                SecondaryButton(
                    text = stringResource(R.string.common__retry),
                    onClick = onClickRetry,
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_clockwise),
                            contentDescription = stringResource(R.string.common__retry),
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    fullWidth = false,
                )
            } else {
                SecondaryButton(
                    text = stringResource(R.string.other__qr_paste),
                    onClick = onClickPaste,
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_clipboard_text_simple),
                            contentDescription = stringResource(R.string.other__qr_paste),
                        )
                    },
                    fullWidth = false,
                )
            }

            FillHeight()
            VerticalSpacer(32.dp)

            PrimaryButton(
                text = stringResource(R.string.other__phone_settings),
                onClick = onClickOpenSettings,
            )
            VerticalSpacer(16.dp)
        }
    }
}

@Preview(showSystemUi = true)
@Composable()
fun PreviewRequired() {
    AppThemeSurface {
        DeniedContent(shouldShowRationale = false)
    }
}

@Preview(showSystemUi = true)
@Composable()
fun PreviewDenied() {
    AppThemeSurface {
        DeniedContent(shouldShowRationale = true)
    }
}

@Preview(showSystemUi = true)
@Composable()
fun PreviewInSheet() {
    AppThemeSurface {
        DeniedContent(
            shouldShowRationale = true,
            inSheet = true,
        )
    }
}
