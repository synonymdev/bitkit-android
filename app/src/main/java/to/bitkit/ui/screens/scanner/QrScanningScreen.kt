@file:OptIn(ExperimentalPermissionsApi::class)

package to.bitkit.ui.screens.scanner

import android.Manifest
import android.content.Context
import android.view.View.LAYER_TYPE_HARDWARE
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.Colors
import to.bitkit.utils.Logger
import java.util.concurrent.Executors
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

@Composable
fun QrScanningScreen(
    navController: NavController,
    onScanSuccess: (String) -> Unit,
) {
    val app = appViewModel ?: return

    // TODO maybe replace & drop accompanist permissions
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    val lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var isFlashlightOn by remember { mutableStateOf(false) }

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

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            uri?.let { processImageFromGallery(context, it, onScanSuccess) }
        }
    )

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { processImageFromGallery(context, it, onScanSuccess) }
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
            ScreenColumn(modifier = Modifier.gradientBackground()) {
                AppTopBar(stringResource(R.string.title_scan), onBackClick = { navController.popBackStack() })
                Content(
                    previewView = previewView,
                    onClickFlashlight = {
                        isFlashlightOn = !isFlashlightOn
                        camera?.cameraControl?.enableTorch(isFlashlightOn)
                    },
                    onClickGallery = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        } else {
                            galleryLauncher.launch("image/*")
                        }
                    }
                )
            }
        }
    )
}

@Composable
private fun Content(
    previewView: PreviewView,
    onClickFlashlight: () -> Unit,
    onClickGallery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .weight(1f)
        ) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds(),
                factory = { previewView.apply { setLayerType(LAYER_TYPE_HARDWARE, null) } }
            )

            IconButton(
                onClick = onClickGallery,
                modifier = Modifier
                    .padding(16.dp)
                    .clip(CircleShape)
                    .background(
                        Colors.White64
                    )
                    .size(48.dp)
                    .align(Alignment.TopStart)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_image_square),
                    contentDescription = null,
                    tint = Colors.White
                )
            }

            IconButton(
                onClick = onClickFlashlight,
                modifier = Modifier
                    .padding(16.dp)
                    .clip(CircleShape)
                    .background(
                        Colors.White64
                    )
                    .size(48.dp)
                    .align(Alignment.TopEnd)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_flashlight),
                    contentDescription = null,
                    tint = Colors.White
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        PrimaryButton(
            icon = {
                Icon(
                    painterResource(R.drawable.ic_clipboard_text_simple),
                    contentDescription = null,
                    tint = Colors.White
                )
            },
            text = stringResource(R.string.other__qr_paste),
            onClick = {} //TODO IMPLEMENT
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
}

private fun processImageFromGallery(
    context: Context,
    uri: Uri,
    onScanSuccess: (String) -> Unit,
) {
    try {
        val image = InputImage.fromFilePath(context, uri)
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        val scanner = BarcodeScanning.getClient(options)

        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    barcode.rawValue?.let { qrCode ->
                        onScanSuccess(qrCode)
                        Logger.info("QR code found $qrCode")
                        return@addOnSuccessListener
                    }
                }
                Logger.error("No QR code found in the image")
            }
            .addOnFailureListener { e ->
                Logger.error("Failed to scan QR code from gallery", e)
            }
    } catch (e: Exception) {
        Logger.error("Failed to process image from gallery", e)
    }
}
