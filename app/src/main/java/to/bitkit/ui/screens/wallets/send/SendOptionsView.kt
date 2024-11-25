package to.bitkit.ui.screens.wallets.send

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import to.bitkit.R
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.ui.appViewModel
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.screens.wallets.send.components.SendButton
import to.bitkit.ui.shared.util.qrCodeScanner
import to.bitkit.ui.theme.AppThemeSurface

@Composable
fun SendOptionsView(
    onComplete: (NewTransactionSheetDetails?) -> Unit,
) {
    val sendViewModel = hiltViewModel<SendViewModel>()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(.875f)
            .imePadding()
    ) {
        val navController = rememberNavController()
        LaunchedEffect(sendViewModel, navController) {
            sendViewModel.effect.collect {
                when (it) {
                    is SendEffect.NavigateToAmount -> navController.navigate(SendRoutes.Amount)
                    is SendEffect.NavigateToAddress -> navController.navigate(SendRoutes.Address)
                    is SendEffect.NavigateToReview -> navController.navigate(SendRoutes.ReviewAndSend)
                    is SendEffect.PaymentSuccess -> onComplete(it.sheet)
                }
            }
        }

        NavHost(
            navController = navController,
            startDestination = SendRoutes.Options,
        ) {
            composable<SendRoutes.Options> {
                SendOptionsContent(
                    onEvent = { sendViewModel.setEvent(it) }
                )
            }
            composable<SendRoutes.Address> {
                val uiState by sendViewModel.uiState.collectAsStateWithLifecycle()
                SendAddressScreen(
                    uiState = uiState,
                    onBack = { navController.popBackStack() },
                    onEvent = { sendViewModel.setEvent(it) },
                )
            }
            composable<SendRoutes.Amount> {
                val uiState by sendViewModel.uiState.collectAsStateWithLifecycle()
                SendAmountScreen(
                    uiState = uiState,
                    onBack = { navController.popBackStack() },
                    onEvent = { sendViewModel.setEvent(it) }
                )
            }
            composable<SendRoutes.ReviewAndSend> {
                val uiState by sendViewModel.uiState.collectAsStateWithLifecycle()
                SendAndReviewScreen(
                    uiState = uiState,
                    onBack = { navController.popBackStack() },
                    onEvent = { sendViewModel.setEvent(it) },
                )
            }
        }
    }
}

@Composable
private fun SendOptionsContent(
    onEvent: (SendEvent) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val app = appViewModel
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        SheetTopBar(stringResource(R.string.title_send))
        Text(
            text = stringResource(R.string.label_to),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Normal,
        )
        Spacer(modifier = Modifier.height(4.dp))

        SendButton(
            label = stringResource(R.string.contact),
            icon = Icons.Default.Person,
            modifier = Modifier.padding(bottom = 4.dp)
        ) {
            scope.launch {
                app?.toast(Exception("Coming soon: Contact"))
            }
        }

        val clipboard = LocalClipboardManager.current
        SendButton(
            label = stringResource(R.string.paste_invoice),
            icon = Icons.Default.ContentPaste,
            modifier = Modifier.padding(bottom = 4.dp)
        ) {
            val uri = clipboard.getText()?.text.orEmpty().trim()
            onEvent(SendEvent.Paste(uri))
        }

        SendButton(
            label = stringResource(R.string.enter_manually),
            icon = Icons.Outlined.Edit,
            modifier = Modifier.padding(bottom = 4.dp)
        ) {
            onEvent(SendEvent.EnterManually)
        }

        val scanner = qrCodeScanner()
        SendButton(
            stringResource(R.string.scan_qr),
            icon = Icons.Default.CenterFocusWeak,
        ) {
            scanner?.startScan()?.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    task.result?.rawValue?.let { data ->
                        onEvent(SendEvent.Scan(data))
                    }
                } else {
                    task.exception?.let {
                        app?.toast(it)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

// region preview
@Preview(showBackground = true)
@Composable
private fun SendOptionsContentPreview() {
    AppThemeSurface {
        SendOptionsContent(
            onEvent = {},
        )
    }
}
// endregion

private object SendRoutes {
    @Serializable
    data object Options

    @Serializable
    data object Address

    @Serializable
    data object Amount

    @Serializable
    data object ReviewAndSend
}
