@file:OptIn(ExperimentalPermissionsApi::class)

package to.bitkit.ui.screens.scanner

import android.Manifest
import android.view.View.LAYER_TYPE_HARDWARE
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
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
import kotlinx.coroutines.withContext
import to.bitkit.R
import to.bitkit.ui.appViewModel
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.utils.Logger
import java.util.concurrent.Executors

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
    val preview by remember { mutableStateOf(Preview.Builder().build()) }
    val imageAnalysis by remember {
        mutableStateOf(
            ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
        )
    }

    LaunchedEffect(lensFacing) {
        imageAnalysis.setAnalyzer(
            Executors.newSingleThreadExecutor(),
            QrCodeAnalyzer { result ->
                if (result.isSuccess) {
                    val qrCode = requireNotNull(result.getOrNull())
                    Logger.debug("Scan success: $qrCode")
                    onScanSuccess(qrCode)
                } else {
                    val error = requireNotNull(result.exceptionOrNull())
                    Logger.error("Failed to scan QR code", error)
                    app.toast(error)
                }
            }
        )
    }

    val cameraSelector = remember(lensFacing) {
        CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()
    }
    var camera by remember { mutableStateOf<Camera?>(null) }

    LaunchedEffect(lensFacing) {
        val cameraProvider = withContext(Dispatchers.IO) {
            ProcessCameraProvider.getInstance(context).get()
        }
        camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
        preview.surfaceProvider = previewView.surfaceProvider
    }
    DisposableEffect(Unit) {
        onDispose {
            camera?.let {
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            }
        }
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
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds(),
            factory = { previewView.apply { setLayerType(LAYER_TYPE_HARDWARE, null) } }
        )
        val (widthInPx, heightInPx, radiusInPx) = with(LocalDensity.current) {
            remember {
                Triple(350.dp.toPx(), 350.dp.toPx(), 16.dp.toPx())
            }
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
