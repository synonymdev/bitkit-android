package to.bitkit.wear.ui.screens.removed

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import to.bitkit.wear.R
import to.bitkit.wear.repositories.WalletRepo
import to.bitkit.wear.ui.components.ListScreen
import to.bitkit.wear.ui.components.WearPreview
import javax.inject.Inject

/** Tells the wearer that the wallet was deleted from the watch, after its seed key was lost. */
@Composable
fun WalletRemovedScreen(
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WalletRemovedViewModel = hiltViewModel(),
) {
    Content(
        onConfirmClick = {
            viewModel.onConfirm()
            onConfirm()
        },
        modifier = modifier,
    )
}

@Composable
private fun Content(
    onConfirmClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListScreen(modifier = modifier) { spec ->
        item {
            ListHeader(
                transformation = SurfaceTransformation(spec),
                modifier = Modifier.transformedHeight(this, spec)
            ) {
                Text(
                    text = stringResource(R.string.removed__title),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("WalletRemovedTitle")
                )
            }
        }
        item {
            Button(
                onClick = onConfirmClick,
                label = { Text(stringResource(R.string.common__ok)) },
                transformation = SurfaceTransformation(spec),
                modifier = Modifier
                    .fillMaxWidth()
                    .transformedHeight(this, spec)
                    .testTag("WalletRemovedConfirm")
            )
        }
    }
}

@HiltViewModel
class WalletRemovedViewModel @Inject constructor(
    private val walletRepo: WalletRepo,
) : ViewModel() {
    fun onConfirm() {
        viewModelScope.launch { walletRepo.acknowledgeRemoval() }
    }
}

@WearPreviewDevices
@Composable
private fun Preview() {
    WearPreview {
        Content(onConfirmClick = {})
    }
}
