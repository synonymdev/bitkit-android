package to.bitkit.ui.screens.scanner

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import android.view.View.LAYER_TYPE_HARDWARE
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.ext.getClipboardText
import to.bitkit.ext.startActivityAppSettings
import to.bitkit.models.Toast
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.TextInput
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppAlertDialog
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.Colors
import to.bitkit.utils.Logger
import to.bitkit.viewmodels.AppViewModel
import java.util.concurrent.Executors

const val SCAN_REQUEST_KEY = "SCAN_REQUEST"
const val SCAN_RESULT_KEY = "SCAN_RESULT"

private const val TAG = "QrScanningScreen"

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun QrScanningScreen(
    navController: NavController,
    inSheet: Boolean = false,
    onBack: () -> Unit = { navController.popBackStack() },
    onScanSuccess: (String) -> Unit,
) {
    val app = appViewModel ?: return

    val (scanResult, setScanResult) = remember { mutableStateOf<String?>(null) }

    // Handle scan result
    LaunchedEffect(scanResult) {
        scanResult?.let { qrCode ->
            delay(100) // wait to prevent navigation result race conditions

            val prev = navController.previousBackStackEntry
            val wasCalledForResult = prev?.savedStateHandle?.contains(SCAN_REQUEST_KEY) == true
            if (wasCalledForResult) {
                prev.savedStateHandle[SCAN_RESULT_KEY] = qrCode
                onBack()
                prev.savedStateHandle.remove<Boolean?>(SCAN_REQUEST_KEY)
            } else {
                onBack()
                onScanSuccess(qrCode)
            }

            // Reset scan result to allow new scans
            setScanResult(null)
        }
    }

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
    val preview = remember { Preview.Builder().build() }
    val analyzer = remember {
        QrCodeAnalyzer { result ->
            if (result.isSuccess) {
                val qrCode = result.getOrThrow()
                Logger.debug("QR code scanned: $qrCode")
                setScanResult(qrCode)
            } else {
                val error = requireNotNull(result.exceptionOrNull())
                Logger.error("Failed to scan QR code", error)
                app.toast(
                    type = Toast.ToastType.ERROR,
                    title = context.getString(R.string.other__qr_error_header),
                    description = context.getString(R.string.other__qr_error_text),
                )
            }
        }
    }
    val imageAnalysis = remember {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            uri?.let { processImageFromGallery(context, it, setScanResult, onError = { e -> app.toast(e) }) }
        }
    )

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { processImageFromGallery(context, it, setScanResult, onError = { e -> app.toast(e) }) }
    }

    LaunchedEffect(lensFacing) {
        imageAnalysis.setAnalyzer(Executors.newSingleThreadExecutor(), analyzer)
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

    CameraPermissionView(
        permissionState = cameraPermissionState,
        deniedContent = {
            DeniedContent(
                shouldShowRationale = cameraPermissionState.status.shouldShowRationale,
                inSheet = inSheet,
                onClickOpenSettings = {
                    context.startActivityAppSettings()
                },
                onClickRetry = cameraPermissionState::launchPermissionRequest,
                onClickPaste = handlePaste(context, app, setScanResult),
                onBack = onBack,
            )
        },
        grantedContent = {
            Column(
                modifier = Modifier
                    .then(if (inSheet) Modifier.gradientBackground() else Modifier)
                    .then(if (inSheet) Modifier.navigationBarsPadding() else Modifier.systemBarsPadding())
            ) {
                if (inSheet) {
                    SheetTopBar(stringResource(R.string.other__qr_scan), onBack = onBack)
                } else {
                    AppTopBar(stringResource(R.string.other__qr_scan), onBackClick = onBack)
                }

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
                    },
                    onPasteFromClipboard = handlePaste(context, app, setScanResult),
                    onSubmitDebug = setScanResult,
                )
            }
        }
    )
}

@Composable
private fun handlePaste(
    context: Context,
    app: AppViewModel,
    setScanResult: (String?) -> Unit,
): () -> Unit = {
    val clipboard = context.getClipboardText()?.trim()
    if (clipboard.isNullOrBlank()) {
        app.toast(
            type = Toast.ToastType.WARNING,
            title = context.getString(R.string.wallet__send_clipboard_empty_title),
            description = context.getString(R.string.wallet__send_clipboard_empty_text),
        )
    }
    setScanResult(clipboard)
}

@Composable
private fun Content(
    previewView: PreviewView,
    onClickFlashlight: () -> Unit,
    onClickGallery: () -> Unit,
    onPasteFromClipboard: () -> Unit,
    modifier: Modifier = Modifier,
    onSubmitDebug: (String?) -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Box(
            modifier = Modifier
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
                    .background(Colors.White64)
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
                    .background(Colors.White64)
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
        VerticalSpacer(16.dp)
        PrimaryButton(
            icon = {
                Icon(
                    painterResource(R.drawable.ic_clipboard_text_simple),
                    contentDescription = stringResource(R.string.other__qr_paste),
                )
            },
            text = stringResource(R.string.other__qr_paste),
            onClick = onPasteFromClipboard,
        )

        @Suppress("KotlinConstantConditions")
        if (Env.isE2eTest) {
            var showDialog by remember { mutableStateOf(false) }
            var debugValue by remember { mutableStateOf("") }
            VerticalSpacer(16.dp)
            SecondaryButton(
                text = "Enter QRCode String",
                onClick = { showDialog = true },
                modifier = Modifier
                    .semantics { testTagsAsResourceId = true }
                    .testTag("ScanPrompt")
            )
            if (showDialog) {
                AppAlertDialog(
                    title = "",
                    confirmText = stringResource(R.string.common__yes_proceed),
                    onConfirm = { onSubmitDebug(debugValue) },
                    onDismiss = { showDialog = false },
                    modifier = Modifier
                        .semantics { testTagsAsResourceId = true }
                        .testTag("QRDialog")
                ) {
                    TextInput(
                        value = debugValue,
                        onValueChange = { debugValue = it },
                        modifier = Modifier
                            .semantics { testTagsAsResourceId = true }
                            .testTag("QRInput")
                    )
                }
            }
        }

        VerticalSpacer(16.dp)
    }
}

private fun processImageFromGallery(
    context: Context,
    uri: Uri,
    onScanSuccess: (String) -> Unit,
    onError: (Throwable) -> Unit,
) {
    runCatching {
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
                onError(Exception("No QR code found in the image"))
            }
            .addOnFailureListener { e ->
                Logger.error("Failed to scan QR code from gallery", e)
                onError(e)
            }
    }.onFailure {
        Logger.error("Failed to process image from gallery", it, context = TAG)
        onError(it)
    }
}
