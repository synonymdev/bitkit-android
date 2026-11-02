package to.bitkit.ui.screens.recovery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import to.bitkit.R
import to.bitkit.data.WalletResetRecovery
import to.bitkit.ui.components.AuthCheckView
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppAlertDialog
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.shared.util.screen
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.viewmodels.AppViewModel
import to.bitkit.viewmodels.SettingsViewModel
import to.bitkit.viewmodels.WalletViewModel

@Composable
fun WalletResetRecoveryScreen(
    walletViewModel: WalletViewModel,
    appViewModel: AppViewModel,
    settingsViewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val walletState by walletViewModel.walletState.collectAsStateWithLifecycle()
    val finishing by walletViewModel.isFinishingWalletReset.collectAsStateWithLifecycle()
    val wiping by walletViewModel.isWiping.collectAsStateWithLifecycle()
    var readRevision by remember { mutableIntStateOf(0) }
    val recoveryPinRequired = remember(appViewModel, walletState.walletResetRecovery, readRevision) {
        appViewModel.recoveryPinRequired
    }
    val pinEnabled by recoveryPinRequired.collectAsStateWithLifecycle(initialValue = null)
    WalletResetRecoveryFlow(
        recovery = walletState.walletResetRecovery,
        busy = finishing || wiping,
        pinEnabled = pinEnabled,
        isCurrent = { expected ->
            walletViewModel.walletState.value.walletResetRecovery == expected &&
                !walletViewModel.isWiping.value && !walletViewModel.isFinishingWalletReset.value
        },
        onRetry = {
            readRevision++
            walletViewModel.refreshWalletResetRecovery()
        },
        onFinish = { walletViewModel.finishPendingWalletReset(it) },
        authentication = { onSuccess, onBack ->
            AuthCheckView(
                appViewModel = appViewModel,
                settingsViewModel = settingsViewModel,
                onSuccess = onSuccess,
                onBack = onBack,
                onValidatePin = appViewModel::validateRecoveryPin,
            )
        },
        phrase = { onBack -> ScopedRecoveryMnemonic(onBack) },
        modifier = modifier,
    )
}

/** This read-only route never admits wallet operations or restores a saved authentication grant. */
@Composable
internal fun WalletResetRecoveryFlow(
    recovery: WalletResetRecovery,
    busy: Boolean,
    pinEnabled: Boolean?,
    isCurrent: (WalletResetRecovery) -> Boolean,
    onRetry: () -> Unit,
    onFinish: (String) -> Unit,
    authentication: @Composable (() -> Unit, () -> Unit) -> Unit,
    phrase: @Composable (() -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentAdmission by rememberUpdatedState(isCurrent)
    key(recovery, busy, pinEnabled) {
        val session = remember { RecoveryPhraseSession() }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val lifecycleState by lifecycle.currentStateAsState()
        DisposableEffect(session, lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) session.revoke()
            }
            lifecycle.addObserver(observer)
            onDispose {
                session.close()
                lifecycle.removeObserver(observer)
            }
        }
        val available = !busy && pinEnabled != null && recovery != WalletResetRecovery.Loading &&
            recovery != WalletResetRecovery.Clear && lifecycleState.isAtLeast(Lifecycle.State.STARTED) &&
            currentAdmission(recovery)
        SideEffect { if (!available) session.revoke() }
        val mayContinue = {
            available && currentAdmission(recovery) && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        val phase = session.phase
        when {
            available && phase is RecoveryPhrasePhase.Authenticate -> {
                BackHandler { session.revoke() }
                authentication({ session.authorize(phase, mayContinue()) }, session::revoke)
            }
            available && phase == RecoveryPhrasePhase.Phrase -> {
                BackHandler { session.revoke() }
                phrase(session::revoke)
            }
            else -> WalletResetRecoveryContent(
                recovery = recovery,
                busy = busy,
                onRetry = onRetry,
                onFinish = onFinish,
                onShowPhrase = { if (mayContinue()) session.begin(requireNotNull(pinEnabled)) },
                canShowPhrase = available,
                modifier = modifier,
            )
        }
    }
}

internal sealed interface RecoveryPhrasePhase {
    data object Overview : RecoveryPhrasePhase
    class Authenticate : RecoveryPhrasePhase
    data object Phrase : RecoveryPhrasePhase
}

internal class RecoveryPhraseSession {
    var phase: RecoveryPhrasePhase by mutableStateOf(RecoveryPhrasePhase.Overview)
        private set
    private var active = true

    fun begin(pinEnabled: Boolean) {
        if (active) phase = if (pinEnabled) RecoveryPhrasePhase.Authenticate() else RecoveryPhrasePhase.Phrase
    }

    fun authorize(attempt: RecoveryPhrasePhase.Authenticate, admitted: Boolean) {
        if (active && phase === attempt && admitted) phase = RecoveryPhrasePhase.Phrase
    }

    fun revoke() { phase = RecoveryPhrasePhase.Overview }

    fun close() {
        active = false
        revoke()
    }
}

@Composable
private fun ScopedRecoveryMnemonic(onBack: () -> Unit) {
    val parent = checkNotNull(LocalViewModelStoreOwner.current as? HasDefaultViewModelProviderFactory)
    val owner = remember(parent) { RecoveryPhraseViewModelOwner(parent) }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    RecoveryMnemonicScreen(onBack, recoveryMnemonicViewModel = hiltViewModel(viewModelStoreOwner = owner))
}

private class RecoveryPhraseViewModelOwner(
    private val parent: HasDefaultViewModelProviderFactory,
) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
    override val viewModelStore = ViewModelStore()
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() = parent.defaultViewModelProviderFactory
    override val defaultViewModelCreationExtras: CreationExtras get() = parent.defaultViewModelCreationExtras
}

@Composable
internal fun WalletResetRecoveryContent(
    recovery: WalletResetRecovery,
    busy: Boolean,
    onRetry: () -> Unit,
    onFinish: (String) -> Unit,
    modifier: Modifier = Modifier,
    onShowPhrase: () -> Unit = {},
    canShowPhrase: Boolean = false,
) {
    var showConfirmation by remember(recovery, busy) { mutableStateOf(false) }
    val attemptId = when (recovery) {
        is WalletResetRecovery.Pending -> recovery.attemptId
        is WalletResetRecovery.Unreadable -> recovery.attemptId
        else -> null
    }
    BackHandler {}
    Column(modifier = modifier.screen().padding(horizontal = 16.dp).testTag("WalletResetRecovery")) {
        AppTopBar(titleText = stringResource(R.string.security__recovery), onBackClick = null)
        VerticalSpacer(16.dp)
        if (!busy && recovery is WalletResetRecovery.Pending) {
            BodyM(text = stringResource(R.string.security__reset_pending_text))
        } else if (!busy &&
            (recovery == WalletResetRecovery.Unavailable || recovery is WalletResetRecovery.Unreadable)
        ) {
            BodyM(text = stringResource(R.string.security__reset_read_error))
        }
        VerticalSpacer(32.dp)
        when {
            busy || recovery == WalletResetRecovery.Loading -> PrimaryButton(
                text = null,
                isLoading = true,
                enabled = false,
                onClick = {},
                modifier = Modifier.testTag("WalletResetProgress"),
            )
            recovery == WalletResetRecovery.Unavailable -> PrimaryButton(
                text = stringResource(R.string.common__retry),
                onClick = onRetry,
                modifier = Modifier.testTag("WalletResetReadRetry")
            )
            attemptId != null -> PrimaryButton(
                text = stringResource(R.string.security__reset_button_reset),
                isLoading = busy,
                enabled = !busy,
                onClick = { showConfirmation = true },
                modifier = Modifier.testTag("WalletResetFinish")
            )
        }
        if (!busy && attemptId != null && !canShowPhrase) {
            VerticalSpacer(16.dp)
            SecondaryButton(text = stringResource(R.string.common__retry), onClick = onRetry)
        }
        if (recovery != WalletResetRecovery.Loading && recovery != WalletResetRecovery.Clear) {
            VerticalSpacer(16.dp)
            SecondaryButton(
                text = stringResource(R.string.security__display_seed),
                enabled = canShowPhrase && !busy,
                onClick = onShowPhrase,
                testTag = "WalletResetShowPhrase",
            )
        }
    }
    if (showConfirmation && attemptId != null && !busy) {
        AppAlertDialog(
            onDismissRequest = { showConfirmation = false },
            title = stringResource(R.string.security__reset_dialog_title),
            text = stringResource(R.string.security__reset_dialog_desc),
            confirmText = stringResource(R.string.security__reset_confirm),
            onConfirm = {
                showConfirmation = false
                onFinish(attemptId)
            },
            onDismiss = { showConfirmation = false },
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        WalletResetRecoveryContent(WalletResetRecovery.Pending("preview"), false, {}, {})
    }
}
