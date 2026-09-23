package to.bitkit.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.flags.OfflineReceiveFeatureFlags
import to.bitkit.ui.components.Caption
import to.bitkit.ui.components.TextInput
import to.bitkit.ui.components.settings.SectionHeader
import to.bitkit.ui.components.settings.SettingsSwitchRow
import to.bitkit.ui.components.settings.SettingsTextButtonRow
import to.bitkit.viewmodels.OfflineReceiveDevSettingsViewModel

/**
 * Dev-only controls for the experimental offline receive provider. Rendered only when the app was compiled with
 * the native adapter; the toggle defaults to off and takes effect after the node is rebuilt.
 */
@Composable
fun OfflineReceiveDevSettingsSection(
    viewModel: OfflineReceiveDevSettingsViewModel = hiltViewModel(),
) {
    if (!OfflineReceiveFeatureFlags.isNativeAvailable) return
    val devSettings by viewModel.devSettings.collectAsStateWithLifecycle()
    val effective by viewModel.effectiveSettings.collectAsStateWithLifecycle()
    var settlementNodeId by rememberSaveable { mutableStateOf(devSettings.settlementNodeId.orEmpty()) }
    var witnessNodeIds by rememberSaveable { mutableStateOf(devSettings.witnessNodeIds.joinToString(",")) }
    LaunchedEffect(devSettings) {
        settlementNodeId = devSettings.settlementNodeId.orEmpty()
        witnessNodeIds = devSettings.witnessNodeIds.joinToString(",")
    }

    SectionHeader("OFFLINE RECEIVE (EXPERIMENTAL)")
    SettingsSwitchRow(
        title = "Offline receive (experimental)",
        subtitle = "Restart the app after changing. Settlement peer: ${effective.settlementNodeId ?: "none"}",
        isChecked = devSettings.isEnabled,
        onClick = { viewModel.setEnabled(!devSettings.isEnabled) },
        switchTestTag = "OfflineReceiveToggle",
    )
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Caption(
            text = "Settlement node id override (empty uses the trusted LSP peer)",
            color = MaterialTheme.colorScheme.secondary,
        )
        TextInput(
            value = settlementNodeId,
            onValueChange = { settlementNodeId = it },
            placeholder = "02...",
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Caption(
            text = "Witness node ids, comma separated",
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(top = 8.dp),
        )
        TextInput(
            value = witnessNodeIds,
            onValueChange = { witnessNodeIds = it },
            placeholder = "02...,03...",
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    SettingsTextButtonRow(
        title = "Save offline receive node ids",
        onClick = {
            viewModel.setSettlementNodeId(settlementNodeId)
            viewModel.setWitnessNodeIds(witnessNodeIds)
        },
    )
}
