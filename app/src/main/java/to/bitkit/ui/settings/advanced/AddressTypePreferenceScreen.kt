package to.bitkit.ui.settings.advanced

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
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
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.settings.SectionHeader
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.components.settings.SettingsButtonValue
import to.bitkit.ui.components.settings.SettingsSwitchRow
import to.bitkit.ui.navigateToHome
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

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

    LaunchedEffect(Unit) {
        viewModel.navigateToHome.collect {
            navController.navigateToHome()
        }
    }

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
    Box(
        content = {
            ScreenColumn {
                AppTopBar(
                    titleText = stringResource(R.string.settings__addr_type__title),
                    onBackClick = onBack,
                    actions = { DrawerNavIcon() },
                )
                Column(
                    content = {
                        SectionHeader(title = stringResource(R.string.settings__addr_type__primary))

                        ADDRESS_TYPES.forEach { type ->
                            val info = type.addressTypeInfo()
                            SettingsButtonRow(
                                title = "${info.shortName} ${info.example}",
                                subtitle = info.description,
                                value = SettingsButtonValue.BooleanValue(uiState.selectedAddressType == type),
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
                                Column(
                                    content = {
                                        SettingsSwitchRow(
                                            title = "${info.shortName} ${info.shortExample}",
                                            subtitle = if (isSelectedType) {
                                                stringResource(R.string.settings__adv__addr_type_currently_selected)
                                            } else {
                                                null
                                            },
                                            isChecked = isMonitored,
                                            onClick = { if (!isSelectedType) onSetMonitoring(type, !isMonitored) },
                                            modifier = Modifier.testTag("MonitorToggle-${type.toAddressTypeE2eId()}"),
                                        )
                                    },
                                    modifier = Modifier.alpha(if (isSelectedType) 0.5f else 1f),
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .testTag("AddressTypePreference"),
                )
            }
            if (uiState.isLoading) {
                Box(
                    content = {
                        AddressTypeLoadingContent(
                            targetAddressType = uiState.loadingAddressType,
                            isMonitoringChange = uiState.isMonitoringChange,
                        )
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Colors.Black),
                )
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun AddressTypeLoadingContent(
    targetAddressType: AddressType?,
    isMonitoringChange: Boolean,
    modifier: Modifier = Modifier,
) {
    val navTitle = if (isMonitoringChange) {
        stringResource(R.string.settings__addr_type__loading_nav_monitoring)
    } else {
        stringResource(R.string.settings__addr_type__loading_nav_address)
    }
    val headline = if (targetAddressType != null && !isMonitoringChange) {
        stringResource(R.string.settings__addr_type__loading_headline)
            .replace("{type}", "<accent>${targetAddressType.addressTypeInfo().shortName}</accent>")
    } else {
        stringResource(R.string.settings__addr_type__loading_updating)
    }
    val description = stringResource(R.string.settings__addr_type__loading_desc)

    Column(
        content = {
            AppTopBar(
                titleText = navTitle,
                onBackClick = null,
                actions = {},
            )
            Column(
                content = {
                    FillHeight()
                    Image(
                        painter = painterResource(R.drawable.wallet),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FillHeight()
                    Column(
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        content = {
                            Display(
                                text = headline.withAccent(accentColor = Colors.Brand),
                            )
                            BodyM(text = description, color = Colors.White64)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    VerticalSpacer(32.dp)
                    CircularProgressIndicator(
                        color = Colors.White32,
                        strokeWidth = 3.dp,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .size(32.dp),
                    )
                    VerticalSpacer(32.dp)
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
            )
        },
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    )
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            uiState = AddressTypePreferenceUiState(
                selectedAddressType = AddressType.P2WPKH,
                monitoredTypes = setOf("nativeSegwit"),
                showMonitoredTypes = true,
            ),
        )
    }
}
