package to.bitkit.ui.screens.scanner

import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.Composable
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraPermissionRequiredView(
    deniedContent: @Composable (PermissionStatus.Denied) -> Unit,
    grantedContent: @Composable () -> Unit,
) {
    val cameraPermissionState = rememberPermissionState(android.Manifest.permission.CAMERA)

    val status = cameraPermissionState.status
    AnimatedContent(
        targetState = status,
        label = "cameraPermissionAnim",
    ) { permissionStatus ->
        when(permissionStatus) {
            is PermissionStatus.Granted -> grantedContent()
            is PermissionStatus.Denied -> deniedContent(permissionStatus)
        }
    }
}
