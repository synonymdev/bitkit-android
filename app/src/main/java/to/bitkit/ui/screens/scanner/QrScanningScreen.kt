@file:OptIn(ExperimentalPermissionsApi::class)

package to.bitkit.ui.screens.scanner

import android.Manifest
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.withContext
import to.bitkit.R
import to.bitkit.ui.appViewModel
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn

@Composable
fun QrScanningScreen(
    navController: NavController,
    onScanSuccess: (String) -> Unit,
) {
    val app = appViewModel ?: return

    // TODO maybe replace & drop accompanist permissions
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    val lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                cameraPermissionState.launchPermissionRequest()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val context = LocalContext.current
    val previewView = remember { PreviewView(context) }
    val preview = Preview.Builder().build()
    val imageAnalysis: ImageAnalysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()

    LaunchedEffect(lensFacing) {
        imageAnalysis.setAnalyzer(
            Dispatchers.Default.asExecutor(),
            QrCodeAnalyzer { result ->
                if (result.isSuccess) {
                    val qrCode = requireNotNull(result.getOrNull())
                    onScanSuccess(qrCode)
                } else {
                    val error = requireNotNull(result.exceptionOrNull())
                    app.toast(error)
                }
            }
        )
    }

    val cameraSelector = CameraSelector.Builder()
        .requireLensFacing(lensFacing)
        .build()
    var camera by remember { mutableStateOf<Camera?>(null) }

    LaunchedEffect(lensFacing) {
        val cameraProvider = ProcessCameraProvider.getInstance(context)
        camera = withContext(Dispatchers.IO) { cameraProvider.get() }
            .bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
        preview.surfaceProvider = previewView.surfaceProvider
    }

    CameraPermissionRequiredView(
        deniedContent = { status ->
            CameraPermissionDeniedScreen(
                requestPermission = cameraPermissionState::launchPermissionRequest,
                shouldShowRationale = status.shouldShowRationale,
            )
        },
        grantedContent = {
            ScreenColumn {
                AppTopBar(stringResource(R.string.title_scan), onBackClick = { navController.popBackStack() })
                Content(previewView = previewView)
            }
        }
    )
}

@Composable
private fun Content(
    previewView: PreviewView,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { previewView }
        )
        val widthInPx: Float
        val heightInPx: Float
        val radiusInPx: Float
        with(LocalDensity.current) {
            widthInPx = 350.dp.toPx()
            heightInPx = 350.dp.toPx()
            radiusInPx = 16.dp.toPx()
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = .5f)),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                modifier = Modifier
                    .size(350.dp)
                    .border(1.dp, Color.White, RoundedCornerShape(16.dp))
            ) {
                val offset = Offset(
                    x = (size.width - widthInPx) / 2,
                    y = (size.height - heightInPx) / 2,
                )
                val cutoutRect = Rect(offset, Size(widthInPx, heightInPx))

                drawRoundRect(
                    topLeft = cutoutRect.topLeft,
                    size = cutoutRect.size,
                    cornerRadius = CornerRadius(radiusInPx, radiusInPx),
                    color = Color.Transparent,
                    blendMode = BlendMode.Clear
                )
            }
        }
    }
}
