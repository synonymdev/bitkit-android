package to.bitkit.ui.utils

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import to.bitkit.ext.requiresPermission

@Composable
fun RequestNotificationPermissions() {
    val context = LocalContext.current

    // Only check permission if running on Android 13+ (SDK 33+)
    val requiresPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        context.requiresPermission(Manifest.permission.POST_NOTIFICATIONS)

    var isGranted by remember { mutableStateOf(!requiresPermission) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        isGranted = it
    }

    LaunchedEffect(isGranted) {
        if (!isGranted && requiresPermission) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
