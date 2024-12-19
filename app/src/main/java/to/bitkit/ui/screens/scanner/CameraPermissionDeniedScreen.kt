package to.bitkit.ui.screens.scanner

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun CameraPermissionDeniedScreen(
    requestPermission: () -> Unit,
    shouldShowRationale: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val textToShow = if (shouldShowRationale) {
            // user has denied the permission but the rationale can be shown
            "The camera is required for scanning. Please grant the permission."
        } else {
            // first time the user sees this, or the user doesn't want to be asked again for this permission
            "Camera permission is required for scanning to be available. Please grant the permission."
        }
        Text(
            modifier = Modifier.padding(horizontal = 16.dp),
            text = textToShow,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = requestPermission) {
            Text("Request permission")
        }
    }
}
