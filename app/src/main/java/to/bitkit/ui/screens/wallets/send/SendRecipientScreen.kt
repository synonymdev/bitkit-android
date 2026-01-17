package to.bitkit.ui.screens.wallets.send

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
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import to.bitkit.R
import to.bitkit.ext.startActivityAppSettings
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.RectangleButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.screens.scanner.QrCodeAnalyzer
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.theme.Shapes
import to.bitkit.ui.theme.TRANSITION_SCREEN_MS
import to.bitkit.ui.toaster
import to.bitkit.ui.utils.withAccent
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import to.bitkit.viewmodels.SendEvent
import java.util.concurrent.Executors
import androidx.camera.core.Preview as CameraPreview

private const val TAG = "SendRecipientScreen"

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun SendRecipientScreen(
    onEvent: (SendEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val toaster = toaster

    // Context & lifecycle
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    // Camera state
    var isFlashlightOn by remember { mutableStateOf(false) }
    var isCameraInitialized by remember { mutableStateOf(false) }
    val previewView = remember {
        PreviewView(context).apply {
            setLayerType(LAYER_TYPE_HARDWARE, null)
        }
    }
    val preview = remember { CameraPreview.Builder().build() }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val executor = remember { Executors.newSingleThreadExecutor() }

    val cameraSelector = remember {
        CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()
    }

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

    // QR code analyzer with auto-proceed callback
    val analyzer = remember(onEvent) {
        QrCodeAnalyzer { result ->
            if (result.isSuccess) {
                val qrCode = result.getOrThrow()
                Logger.debug("QR scanned: '$qrCode'", context = TAG)
                onEvent(SendEvent.AddressContinue(qrCode))
            } else {
                val error = requireNotNull(result.exceptionOrNull())
                Logger.error("Scan failed", error, context = TAG)
                toaster.error(
                    title = R.string.other__qr_error_header,
                    body = R.string.other__qr_error_text,
                )
            }
        }
    }

    val imageAnalysis = remember {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    }

    LaunchedEffect(cameraPermissionState.status, isCameraInitialized) {
        if (cameraPermissionState.status.isGranted && !isCameraInitialized) {
            runCatching {
                delay(TRANSITION_SCREEN_MS)
                imageAnalysis.setAnalyzer(executor, analyzer)

                val cameraProvider = withContext(Dispatchers.IO) {
                    ProcessCameraProvider.getInstance(context).get()
                }
                camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
                preview.surfaceProvider = previewView.surfaceProvider
                isCameraInitialized = true
            }.onFailure {
                Logger.error("Camera initialization failed", it, context = TAG)
                toaster.error(
                    title = context.getString(R.string.other__qr_error_header),
                    body = context.getString(R.string.other__camera_init_error)
                        .replace("{message}", it.message.orEmpty()),
                )
                isCameraInitialized = false
            }
        }
    }

    // Camera cleanup
    DisposableEffect(Unit) {
        onDispose {
            camera?.let {
                runCatching {
                    ProcessCameraProvider.getInstance(context).get().unbindAll()
                }.onFailure {
                    Logger.error("Camera cleanup failed", it, context = TAG)
                }
            }
            // Reset state - camera will reinit if needed on next composition
            isCameraInitialized = false
            executor.shutdown()
        }
    }

    // Gallery picker launchers
    val handleGalleryScanSuccess = { qrCode: String ->
        Logger.debug("QR from gallery: $qrCode", context = TAG)
        onEvent(SendEvent.AddressContinue(qrCode))
    }

    val handleGalleryError: (Throwable) -> Unit = { toaster.error(it) }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            uri?.let {
                processImageFromGallery(
                    context = context,
                    uri = it,
                    onScanSuccess = handleGalleryScanSuccess,
                    onError = handleGalleryError,
                )
            }
        }
    )

    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let {
            processImageFromGallery(
                context = context,
                uri = it,
                onScanSuccess = handleGalleryScanSuccess,
                onError = handleGalleryError,
            )
        }
    }

    SendRecipientContent(
        previewView = previewView,
        onClickFlashlight = {
            camera?.cameraControl?.let { control ->
                isFlashlightOn = !isFlashlightOn
                runCatching {
                    control.enableTorch(isFlashlightOn)
                }.onFailure {
                    Logger.error("Torch control failed", it, context = TAG)
                    // Revert state
                    isFlashlightOn = !isFlashlightOn
                }
            }
        },
        onClickGallery = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pickMedia.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            } else {
                galleryLauncher.launch("image/*")
            }
        },
        onClickContact = { onEvent(SendEvent.Contacts) },
        onClickPaste = { onEvent(SendEvent.Paste) },
        onClickManual = { onEvent(SendEvent.EnterManually) },
        cameraPermissionGranted = cameraPermissionState.status.isGranted,
        onRequestPermission = { context.startActivityAppSettings() },
        modifier = modifier,
    )
}

@Composable
private fun SendRecipientContent(
    previewView: PreviewView?,
    onClickFlashlight: () -> Unit,
    onClickGallery: () -> Unit,
    onClickContact: () -> Unit,
    onClickPaste: () -> Unit,
    onClickManual: () -> Unit,
    cameraPermissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
    ) {
        SheetTopBar(titleText = stringResource(R.string.wallet__send_bitcoin))
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (cameraPermissionGranted && previewView != null) {
                    CameraPreviewWithControls(
                        previewView = previewView,
                        onClickFlashlight = onClickFlashlight,
                        onClickGallery = onClickGallery,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    PermissionDenied(
                        onClickRetry = onRequestPermission,
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight()
                    )
                }
            }

            RectangleButton(
                label = stringResource(R.string.wallet__recipient_contact),
                icon = R.drawable.ic_users,
                iconTint = Colors.Brand,
                modifier = Modifier.testTag("RecipientContact")
            ) {
                onClickContact()
            }

            RectangleButton(
                label = stringResource(R.string.wallet__recipient_invoice),
                icon = R.drawable.ic_clipboard_text,
                iconTint = Colors.Brand,
                modifier = Modifier.testTag("RecipientInvoice")
            ) {
                onClickPaste()
            }

            RectangleButton(
                label = stringResource(R.string.wallet__recipient_manual),
                icon = R.drawable.ic_pencil_simple,
                iconTint = Colors.Brand,
                modifier = Modifier.testTag("RecipientManual")
            ) {
                onClickManual()
            }
        }
    }
}

@Composable
private fun CameraPreviewWithControls(
    previewView: PreviewView,
    onClickFlashlight: () -> Unit,
    onClickGallery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(Shapes.medium)
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds(),
            factory = { previewView }
        )

        // Gallery button (top-left)
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

        BodyMSB(
            stringResource(R.string.other__camera_scan_qr),
            color = Colors.White,
            modifier = Modifier
                .padding(top = 31.dp)
                .align(Alignment.TopCenter)
        )

        // Flashlight button (top-right)
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
}

@Composable
private fun PermissionDenied(
    onClickRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(Shapes.medium)
            .background(Colors.Black)
            .padding(32.dp)
    ) {
        Display(
            stringResource(R.string.other__camera_permission_title)
                .withAccent(accentColor = Colors.Brand),
            color = Colors.White
        )

        VerticalSpacer(8.dp)

        BodyM(
            stringResource(R.string.other__camera_permission_description),
            color = Colors.White64,
            modifier = Modifier.fillMaxWidth()
        )

        VerticalSpacer(32.dp)

        PrimaryButton(
            text = stringResource(R.string.other__camera_permission_button),
            icon = {
                Icon(painter = painterResource(R.drawable.ic_camera), contentDescription = null)
            },
            onClick = onClickRetry,
        )
    }
}

private fun processImageFromGallery(
    context: Context,
    uri: Uri,
    onScanSuccess: (String) -> Unit,
    onError: (Throwable) -> Unit,
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
                        Logger.info("QR from gallery: $qrCode", context = TAG)
                        return@addOnSuccessListener
                    }
                }
                Logger.error("No QR code in image", context = TAG)
                onError(AppError(context.getString(R.string.other__qr_error_text)))
            }
            .addOnFailureListener {
                Logger.error("Gallery scan failed", it, context = TAG)
                onError(it)
            }
    } catch (e: IllegalArgumentException) {
        Logger.error("Gallery processing failed", e, context = TAG)
        onError(e)
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            SendRecipientContent(
                previewView = null,
                onClickFlashlight = {},
                onClickGallery = {},
                onClickContact = {},
                onClickPaste = {},
                onClickManual = {},
                cameraPermissionGranted = false,
                onRequestPermission = {},
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}
