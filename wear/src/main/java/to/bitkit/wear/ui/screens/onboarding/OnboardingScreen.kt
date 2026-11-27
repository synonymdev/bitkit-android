package to.bitkit.wear.ui.screens.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import to.bitkit.wear.R
import to.bitkit.wear.repositories.WalletRepo
import to.bitkit.wear.ui.components.CenteredScreen
import to.bitkit.wear.ui.components.WearPreview
import to.bitkit.wear.ui.components.WearPreviewDevices
import javax.inject.Inject

/**
 * The Bitkit mark and the one way in for now: restoring a recovery phrase. Each time it comes on screen it checks
 * for a stored wallet and leaves through [onWalletFind] when there is one, as after a restore whose save finished
 * while the wearer was away.
 */
@Composable
fun OnboardingScreen(
    onRestoreClick: () -> Unit,
    onWalletFind: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val currentOnWalletFind by rememberUpdatedState(onWalletFind)
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { currentOnWalletFind() }
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose {}
    }
    Content(
        onRestoreClick = onRestoreClick,
        modifier = modifier
    )
}

@Composable
private fun Content(
    onRestoreClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CenteredScreen(
        edgeButton = {
            EdgeButton(
                onClick = onRestoreClick,
                buttonSize = EdgeButtonSize.Medium,
                modifier = Modifier.testTag("WatchOnboardingRestore")
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_restore),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Text(stringResource(R.string.onboarding__restore))
            }
        },
        modifier = modifier
    ) {
        Image(
            painter = painterResource(R.drawable.bitkit_logo),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .testTag("WatchOnboardingLogo")
        )
    }
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val walletRepo: WalletRepo,
) : ViewModel() {
    private val _effects = MutableSharedFlow<OnboardingEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<OnboardingEffect> = _effects.asSharedFlow()

    fun onResume() {
        viewModelScope.launch {
            if (walletRepo.hasWallet()) _effects.emit(OnboardingEffect.WalletFound)
        }
    }
}

sealed interface OnboardingEffect {
    data object WalletFound : OnboardingEffect
}

@WearPreviewDevices
@Composable
private fun Preview() {
    WearPreview {
        Content(onRestoreClick = {})
    }
}
