package to.bitkit.ui.screens.contacts

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.ImmutableList
import to.bitkit.R
import to.bitkit.ext.getClipboardText
import to.bitkit.models.PubkyProfile
import to.bitkit.models.PubkyProfileLink
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.BottomSheet
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.CenteredProfileHeader
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.SheetSize
import to.bitkit.ui.components.Text13Up
import to.bitkit.ui.components.TextInput
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

// region AddContactSheet (bottom sheet)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactSheet(
    currentPublicKey: String?,
    contacts: ImmutableList<PubkyProfile>,
    onDismiss: () -> Unit,
    onSubmit: (publicKey: String) -> Unit,
    onExistingContact: (publicKey: String) -> Unit,
    onScanQr: () -> Unit,
) {
    val context = LocalContext.current
    var publicKeyInput by remember { mutableStateOf("") }
    val validationResult = resolveAddContactValidation(
        input = publicKeyInput,
        ownPublicKey = currentPublicKey,
        contacts = contacts,
    )
    val validationMessage = when (validationResult) {
        AddContactValidationResult.Empty -> null
        is AddContactValidationResult.ExistingContact -> context.getString(R.string.contacts__add_error_existing)
        AddContactValidationResult.InvalidKey -> context.getString(R.string.contacts__add_error_invalid_key)
        AddContactValidationResult.OwnKey -> context.getString(R.string.contacts__add_error_self)
        is AddContactValidationResult.Valid -> null
    }
    BottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        AddContactSheetContent(
            publicKeyInput = publicKeyInput,
            validationMessage = validationMessage,
            isSubmitEnabled = validationResult is AddContactValidationResult.Valid,
            onPublicKeyChange = { publicKeyInput = PubkyPublicKeyFormat.bounded(it) },
            onPaste = {
                context.getClipboardText()?.trim()?.let {
                    publicKeyInput = PubkyPublicKeyFormat.bounded(it)
                    val pastedValidation = resolveAddContactValidation(
                        input = publicKeyInput,
                        ownPublicKey = currentPublicKey,
                        contacts = contacts,
                    )
                    if (pastedValidation is AddContactValidationResult.ExistingContact) {
                        onExistingContact(pastedValidation.normalizedKey)
                    }
                }
            },
            onScanQr = onScanQr,
            onSubmit = {
                val normalizedKey = (validationResult as? AddContactValidationResult.Valid)?.normalizedKey
                    ?: return@AddContactSheetContent
                onSubmit(normalizedKey)
            },
        )
    }
}

@Composable
private fun AddContactSheetContent(
    publicKeyInput: String,
    validationMessage: String?,
    isSubmitEnabled: Boolean,
    onPublicKeyChange: (String) -> Unit,
    onPaste: () -> Unit,
    onScanQr: () -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .sheetHeight(SheetSize.SMALL, isModal = true)
            .gradientBackground()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        SheetTopBar(titleText = stringResource(R.string.contacts__add_sheet_title))
        VerticalSpacer(16.dp)

        BodyM(
            text = stringResource(R.string.contacts__add_description),
            color = Colors.White64,
        )
        VerticalSpacer(16.dp)

        Text13Up(text = stringResource(R.string.contacts__add_pubky_label))
        VerticalSpacer(8.dp)
        TextInput(
            value = publicKeyInput,
            onValueChange = onPublicKeyChange,
            placeholder = stringResource(R.string.contacts__add_pubky_placeholder),
            minLines = 2,
            maxLines = 2,
            isError = validationMessage != null,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
            ),
            supportingText = validationMessage?.let { message ->
                {
                    BodyS(text = message, color = Colors.Red)
                }
            },
            trailingIcon = {
                IconButton(
                    onClick = onPaste,
                    modifier = Modifier.testTag("AddContactPaste")
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_clipboard_text),
                        contentDescription = null,
                        tint = Colors.White64,
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("AddContactPubkyField")
        )
        VerticalSpacer(16.dp)

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            SecondaryButton(
                text = stringResource(R.string.contacts__add_scan_qr),
                onClick = onScanQr,
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_scan),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag("AddContactScanQR")
            )
            PrimaryButton(
                text = stringResource(R.string.contacts__add_button),
                onClick = onSubmit,
                enabled = isSubmitEnabled,
                modifier = Modifier
                    .weight(1f)
                    .testTag("AddContactAdd")
            )
        }
        VerticalSpacer(16.dp)
    }
}

// endregion

// region AddContactScreen (full screen)

@Composable
fun AddContactScreen(
    viewModel: AddContactViewModel,
    onBackClick: () -> Unit,
    onContactSaved: (String) -> Unit,
    onPayContact: (String, String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.effects.collect {
            when (it) {
                is AddContactEffect.ContactSaved -> onContactSaved(it.publicKey)
                is AddContactEffect.OpenPayment -> onPayContact(it.paymentRequest, it.publicKey)
            }
        }
    }

    Content(
        uiState = uiState,
        onBackClick = onBackClick,
        onPay = { viewModel.payContact() },
        onSave = { viewModel.saveContact() },
        onRetry = { viewModel.fetchProfile(uiState.publicKeyInput) },
    )
}

@Composable
private fun Content(
    uiState: AddContactUiState,
    onBackClick: () -> Unit,
    onPay: () -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.contacts__add_contact_title),
            onBackClick = onBackClick,
            actions = { DrawerNavIcon() },
        )

        when {
            uiState.isLoading && uiState.fetchedProfile == null -> LoadingContent(
                publicKey = uiState.publicKeyInput,
            )
            uiState.error != null && uiState.fetchedProfile == null -> ErrorContent(
                error = uiState.error,
                onRetry = onRetry,
            )
            uiState.fetchedProfile != null -> LoadedContent(
                profile = uiState.fetchedProfile,
                isLoading = uiState.isLoading,
                hasPublicPaymentEndpoint = uiState.hasPublicPaymentEndpoint,
                onPay = onPay,
                onSave = onSave,
            )
        }
    }
}

private const val ELLIPSE_ANIMATION_DURATION_MS = 8000

@Composable
private fun LoadingContent(publicKey: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp)
    ) {
        VerticalSpacer(24.dp)

        Text13Up(
            text = PubkyPublicKeyFormat.display(publicKey),
            color = Colors.White64,
        )

        VerticalSpacer(16.dp)

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(Colors.Gray5)
        ) {
            Display(
                text = publicKey.take(1).uppercase(),
                fontSize = 28.sp,
            )
        }

        VerticalSpacer(16.dp)

        Display(
            text = stringResource(R.string.contacts__add_retrieving),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            RotatingEllipses(modifier = Modifier.size(256.dp))
        }
    }
}

@Composable
private fun RotatingEllipses(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "ellipses")
    val outerRotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(ELLIPSE_ANIMATION_DURATION_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "outer",
    )
    val innerRotation by transition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(ELLIPSE_ANIMATION_DURATION_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "inner",
    )
    val green = Colors.PubkyGreen

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .drawBehind {
                val dashOn = 12.dp.toPx()
                val dashOff = 8.dp.toPx()
                val dashedStroke = Stroke(
                    width = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashOn, dashOff)),
                )

                val outerDiameter = size.width * 1.2f
                val outerSize = Size(outerDiameter, outerDiameter)
                val outerOffset = Offset(
                    (size.width - outerDiameter) / 2f,
                    (size.height - outerDiameter) / 2f,
                )
                rotate(outerRotation) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            0f to green,
                            0.5f to green.copy(alpha = 0.1f),
                            1f to green,
                        ),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = outerOffset,
                        size = outerSize,
                        style = dashedStroke,
                    )
                }

                val innerDiameter = size.width * 0.8f
                val innerSize = Size(innerDiameter, innerDiameter)
                val innerOffset = Offset(
                    (size.width - innerDiameter) / 2f,
                    (size.height - innerDiameter) / 2f,
                )
                rotate(innerRotation) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            0f to green,
                            0.5f to green.copy(alpha = 0.1f),
                            1f to green,
                        ),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = innerOffset,
                        size = innerSize,
                        style = dashedStroke,
                    )
                }
            }
    ) {
        Image(
            painter = painterResource(R.drawable.card),
            contentDescription = null,
            modifier = Modifier.size(128.dp)
        )
    }
}

@Composable
private fun ErrorContent(
    error: String,
    onRetry: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp)
    ) {
        BodyM(text = error, color = Colors.White64, textAlign = TextAlign.Center)
        VerticalSpacer(16.dp)
        SecondaryButton(
            text = stringResource(R.string.common__retry),
            onClick = onRetry,
            modifier = Modifier.testTag("AddContactRetry")
        )
    }
}

@Composable
private fun LoadedContent(
    profile: PubkyProfile,
    isLoading: Boolean,
    hasPublicPaymentEndpoint: Boolean,
    onPay: () -> Unit,
    onSave: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        VerticalSpacer(24.dp)

        CenteredProfileHeader(
            publicKey = profile.publicKey,
            name = profile.name,
            bio = profile.bio,
            imageUrl = profile.imageUrl,
        )

        FillHeight()

        BodyS(
            text = stringResource(R.string.contacts__add_privacy_notice, profile.name),
            color = Colors.White64,
        )
        VerticalSpacer(16.dp)

        if (hasPublicPaymentEndpoint) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                SecondaryButton(
                    text = stringResource(R.string.contacts__add_pay),
                    onClick = onPay,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("AddContactPay")
                )
                PrimaryButton(
                    text = stringResource(R.string.common__save),
                    onClick = onSave,
                    enabled = !isLoading,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("AddContactSave")
                )
            }
        } else {
            PrimaryButton(
                text = stringResource(R.string.common__save),
                onClick = onSave,
                enabled = !isLoading,
                modifier = Modifier.testTag("AddContactSave")
            )
        }
        VerticalSpacer(16.dp)
    }
}

// endregion

// region Previews

@Preview
@Composable
private fun SheetPreview() {
    AppThemeSurface {
        BottomSheetPreview {
            AddContactSheetContent(
                publicKeyInput = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg",
                validationMessage = null,
                isSubmitEnabled = true,
                onPublicKeyChange = {},
                onPaste = {},
                onScanQr = {},
                onSubmit = {},
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun LoadingPreview() {
    AppThemeSurface {
        Content(
            uiState = AddContactUiState(
                publicKeyInput = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg",
                isLoading = true,
            ),
            onBackClick = {},
            onPay = {},
            onSave = {},
            onRetry = {},
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun LoadedPreview() {
    AppThemeSurface {
        Content(
            uiState = AddContactUiState(
                publicKeyInput = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg",
                fetchedProfile = PubkyProfile(
                    publicKey = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg",
                    name = "John Carvalho",
                    bio = "CEO at @synonym_to",
                    imageUrl = null,
                    links = listOf(PubkyProfileLink("Website", "https://synonym.to")),
                    status = null,
                ),
            ),
            onBackClick = {},
            onPay = {},
            onSave = {},
            onRetry = {},
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun ErrorPreview() {
    AppThemeSurface {
        Content(
            uiState = AddContactUiState(
                publicKeyInput = "invalid_key",
                error = "Could not retrieve contact info. Please check the public key and try again.",
            ),
            onBackClick = {},
            onPay = {},
            onSave = {},
            onRetry = {},
        )
    }
}

// endregion
