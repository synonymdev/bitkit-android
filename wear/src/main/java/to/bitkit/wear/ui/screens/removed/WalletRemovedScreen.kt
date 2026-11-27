package to.bitkit.wear.ui.screens.removed

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
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

/** Tells the wearer that the wallet was deleted from the watch, after its seed key was lost. */
@Composable
fun WalletRemovedScreen(
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WalletRemovedViewModel = hiltViewModel(),
) {
    val currentOnConfirm by rememberUpdatedState(onConfirm)
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { currentOnConfirm() }
    }
    Content(
        onConfirmClick = viewModel::onConfirm,
        modifier = modifier
    )
}

@Composable
private fun Content(
    onConfirmClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CenteredScreen(
        edgeButton = {
            EdgeButton(
                onClick = onConfirmClick,
                buttonSize = EdgeButtonSize.Medium,
                modifier = Modifier.testTag("WalletRemovedConfirm")
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = stringResource(R.string.common__ok),
                )
            }
        },
        modifier = modifier
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_delete),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(32.dp)
        )
        Text(
            text = stringResource(R.string.removed__title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("WalletRemovedTitle")
        )
    }
}

@HiltViewModel
class WalletRemovedViewModel @Inject constructor(
    private val walletRepo: WalletRepo,
) : ViewModel() {
    private val _effects = MutableSharedFlow<WalletRemovedEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<WalletRemovedEffect> = _effects.asSharedFlow()

    private var confirmJob: Job? = null

    /** Leaves the screen once the acknowledgement is stored, so the notice does not show again. */
    fun onConfirm() {
        if (confirmJob?.isActive == true) return
        confirmJob = viewModelScope.launch {
            walletRepo.acknowledgeRemoval()
            _effects.emit(WalletRemovedEffect.Acknowledged)
        }
    }
}

sealed interface WalletRemovedEffect {
    data object Acknowledged : WalletRemovedEffect
}

@WearPreviewDevices
@Composable
private fun Preview() {
    WearPreview {
        Content(onConfirmClick = {})
    }
}
