package to.bitkit.ui.settings.advanced

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.synonym.bitkitcore.AddressType
import to.bitkit.R
import to.bitkit.models.addressTypeInfo
import to.bitkit.models.toSettingsString
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.settings.SectionHeader
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.components.settings.SettingsButtonValue
import to.bitkit.ui.components.settings.SettingsSwitchRow
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface

private val ADDRESS_TYPES = listOf(
    AddressType.P2PKH,
    AddressType.P2SH,
    AddressType.P2WPKH,
    AddressType.P2TR,
)

private fun AddressType.toAddressTypeE2eId(): String = when (this) {
    AddressType.P2PKH -> "p2pkh"
    AddressType.P2SH -> "p2sh-p2wpkh"
    AddressType.P2WPKH -> "p2wpkh"
    AddressType.P2TR -> "p2tr"
    else -> "p2wpkh"
}

@Composable
fun AddressTypePreferenceScreen(
    navController: NavController,
    viewModel: AddressTypePreferenceViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Content(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onSelectAddressType = viewModel::updateAddressType,
        onSetMonitoring = viewModel::setMonitoring,
    )
}

@Composable
private fun Content(
    uiState: AddressTypePreferenceUiState,
    onBack: () -> Unit = {},
    onSelectAddressType: (AddressType) -> Unit = {},
    onSetMonitoring: (AddressType, Boolean) -> Unit = { _, _ -> },
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.settings__addr_type__title),
            onBackClick = onBack,
            actions = { DrawerNavIcon() },
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .testTag("AddressTypePreference"),
        ) {
            SectionHeader(title = stringResource(R.string.settings__addr_type__primary))

            ADDRESS_TYPES.forEach { type ->
                val info = type.addressTypeInfo()
                SettingsButtonRow(
                    title = "${info.shortName} ${info.example}",
                    subtitle = info.description,
                    value = SettingsButtonValue.BooleanValue(uiState.selectedAddressType == type),
                    enabled = !uiState.isLoading,
                    onClick = { onSelectAddressType(type) },
                    modifier = Modifier.testTag(type.toAddressTypeE2eId()),
                )
            }

            if (uiState.showMonitoredTypes) {
                SectionHeader(title = stringResource(R.string.settings__addr_type__monitoring))

                ADDRESS_TYPES.forEach { type ->
                    val info = type.addressTypeInfo()
                    val isMonitored = type.toSettingsString() in uiState.monitoredTypes
                    val isSelectedType = uiState.selectedAddressType == type
                    val isDisabled = isSelectedType || uiState.isLoading
                    SettingsSwitchRow(
                        title = "${info.shortName} ${info.shortExample}",
                        subtitle = if (isSelectedType) {
                            stringResource(R.string.settings__adv__addr_type_currently_selected)
                        } else {
                            null
                        },
                        isChecked = isMonitored,
                        onClick = { if (!isDisabled) onSetMonitoring(type, !isMonitored) },
                        modifier = Modifier
                            .alpha(if (isDisabled) 0.5f else 1f)
                            .testTag("MonitorToggle-${type.toAddressTypeE2eId()}"),
                    )
                }
            }

            VerticalSpacer(16.dp)
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            uiState = AddressTypePreferenceUiState(
                showMonitoredTypes = true,
            ),
        )
    }
}
