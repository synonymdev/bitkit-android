package to.bitkit.ui.settings.advanced

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.synonym.bitkitcore.AddressType
import to.bitkit.R
import to.bitkit.models.addressTypeInfo
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.settings.SectionHeader
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.components.settings.SettingsButtonValue
import to.bitkit.ui.navigateToHome
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface

@Composable
fun AddressTypePreferenceScreen(
    navController: NavController,
    viewModel: AddressTypePreferenceViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Content(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onClose = { navController.navigateToHome() },
        onClickBitcoinAddressType = { addressType ->
            viewModel.setAddressType(addressType)
            navController.popBackStack()
        },
    )
}

@Composable
private fun Content(
    uiState: AddressTypePreferenceUiState,
    onBack: () -> Unit = {},
    onClose: () -> Unit = {},
    onClickBitcoinAddressType: (AddressType) -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.settings__adv__address_type),
            onBackClick = onBack,
            actions = { CloseNavIcon(onClose) },
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SectionHeader(title = stringResource(R.string.settings__adv__address_type))

            uiState.availableAddressTypes.forEach { addressType ->
                val info = addressType.addressTypeInfo()
                SettingsButtonRow(
                    title = "${info.name} ${info.example}",
                    subtitle = info.description,
                    value = SettingsButtonValue.BooleanValue(addressType == uiState.selectedAddressType),
                    onClick = { onClickBitcoinAddressType(addressType) },
                )
            }

            VerticalSpacer(32.dp)
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            uiState = AddressTypePreferenceUiState(
                availableAddressTypes = listOf(AddressType.P2WPKH, /* AddressType.P2SH, AddressType.P2PKH */)
            ),
        )
    }
}
