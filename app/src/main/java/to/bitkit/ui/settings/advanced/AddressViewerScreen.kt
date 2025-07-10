package to.bitkit.ui.settings.advanced

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.ext.setClipboardText
import to.bitkit.models.AddressModel
import to.bitkit.models.Toast
import to.bitkit.models.formatToModernDisplay
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Caption
import to.bitkit.ui.components.CaptionB
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.QrCodeImage
import to.bitkit.ui.components.SearchInput
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.navigateToHome
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.theme.AppShapes
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.BlockExplorerType
import to.bitkit.ui.utils.getBlockExplorerUrl

@Composable
fun AddressViewerScreen(
    navController: NavController,
    viewModel: AddressViewerViewModel = hiltViewModel(),
) {
    val app = appViewModel ?: return
    val context = LocalContext.current

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    AddressViewerContent(
        uiState = uiState,
        onBack = { navController.popBackStack() },
        onClose = { navController.navigateToHome() },
        onSearchTextChanged = viewModel::updateSearchText,
        onAddressSelected = { address -> viewModel.selectAddress(address) },
        onSwitchAddressType = viewModel::switchAddressType,
        onClickOpenBlockExplorer = { address ->
            val url = getBlockExplorerUrl(address, BlockExplorerType.ADDRESS)
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            context.startActivity(intent)
        },
        onClickCheckBalances = viewModel::refreshBalances,
        onGenerateMoreAddresses = viewModel::loadMoreAddresses,
        onCopy = { text ->
            context.setClipboardText(text)
            app.toast(
                type = Toast.ToastType.SUCCESS,
                title = context.getString(R.string.common__copied),
                description = text,
            )
        }
    )
}

@Composable
private fun AddressViewerContent(
    uiState: UiState,
    onBack: () -> Unit = {},
    onClose: () -> Unit = {},
    onSearchTextChanged: (String) -> Unit = {},
    onAddressSelected: (AddressModel) -> Unit = {},
    onSwitchAddressType: (Boolean) -> Unit = {},
    onClickOpenBlockExplorer: (String) -> Unit = {},
    onClickCheckBalances: () -> Unit = {},
    onGenerateMoreAddresses: () -> Unit = {},
    onCopy: (String) -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.settings__adv__address_viewer),
            onBackClick = onBack,
            actions = { CloseNavIcon(onClose) },
        )

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
        ) {
            VerticalSpacer(16.dp)
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth()
            ) {
                QrCodeImage(
                    content = uiState.selectedAddress?.address.orEmpty(),
                    size = 120.dp,
                    modifier = Modifier
                        .size(120.dp)
                        .clickableAlpha { onCopy(uiState.selectedAddress?.address.orEmpty()) }
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    BodyS(
                        text = stringResource(R.string.settings__addr__index)
                            .replace("{index}", (uiState.selectedAddress?.index ?: 0).toString()),
                        color = Colors.White80
                    )
                    BodyS(
                        text = stringResource(R.string.settings__addr__path)
                            .replace("{path}", uiState.selectedAddress?.path.orEmpty()),
                        color = Colors.White80
                    )
                    BodyS(
                        text = stringResource(R.string.wallet__activity_explorer),
                        color = Colors.White80,
                        modifier = Modifier.clickableAlpha {
                            onClickOpenBlockExplorer(uiState.selectedAddress?.address.orEmpty())
                        }
                    )
                }
            }
            VerticalSpacer(16.dp)

            SearchInput(
                value = uiState.searchText,
                onValueChange = onSearchTextChanged,
                placeholder = stringResource(R.string.common__search),
                modifier = Modifier.fillMaxWidth()
            )
            VerticalSpacer(16.dp)

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                PrimaryButton(
                    text = stringResource(R.string.settings__addr__addr_change),
                    size = ButtonSize.Small,
                    onClick = { onSwitchAddressType(false) },
                    color = if (uiState.showReceiveAddresses) Colors.White16 else Colors.Brand,
                    modifier = Modifier.weight(1f)
                )
                PrimaryButton(
                    text = stringResource(R.string.settings__addr__addr_receiving),
                    size = ButtonSize.Small,
                    color = if (uiState.showReceiveAddresses) Colors.Brand else Colors.White16,
                    onClick = { onSwitchAddressType(true) },
                    modifier = Modifier.weight(1f)
                )
            }
            VerticalSpacer(16.dp)

            // Address List
            val filteredAddresses = remember(uiState.addresses, uiState.searchText) {
                uiState.addresses.filter { address ->
                    if (uiState.searchText.isBlank()) true
                    else address.address.contains(uiState.searchText, ignoreCase = true)
                }
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (uiState.addresses.isEmpty()) {
                    item {
                        ListMessage(stringResource(R.string.settings__addr__loading))
                    }
                } else if (filteredAddresses.isEmpty()) {
                    item {
                        ListMessage(
                            text = stringResource(R.string.settings__addr__no_addrs_str)
                                .replace("{searchTxt}", uiState.searchText)
                        )
                    }
                }
                items(filteredAddresses) { address ->
                    AddressItem(
                        index = address.index,
                        address = address.address,
                        balance = uiState.balances[address.address] ?: 0,
                        isSelected = address.address == uiState.selectedAddress?.address,
                        onClick = { onAddressSelected(address) },
                    )
                }
            }

            VerticalSpacer(16.dp)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                SecondaryButton(
                    text = stringResource(R.string.settings__addr__gen_20),
                    size = ButtonSize.Small,
                    enabled = uiState.addresses.isNotEmpty(),
                    isLoading = uiState.isLoading,
                    onClick = onGenerateMoreAddresses,
                    modifier = Modifier.weight(1f)
                )

                PrimaryButton(
                    text = stringResource(R.string.settings__addr__check_balances),
                    size = ButtonSize.Small,
                    enabled = uiState.addresses.isNotEmpty(),
                    isLoading = uiState.isLoadingBalances,
                    onClick = onClickCheckBalances,
                    modifier = Modifier.weight(1f)
                )
            }
            VerticalSpacer(16.dp)
        }
    }
}

@Composable
private fun ListMessage(text: String) {
    Caption(
        text = text,
        textAlign = TextAlign.Center,
        color = Colors.White64,
        modifier = Modifier
            .padding(vertical = 16.dp)
            .fillMaxWidth()
    )
}

@Composable
private fun AddressItem(
    index: Int,
    address: String,
    balance: Long,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = if (isSelected) Colors.Brand else Colors.White08
    val textColor = if (isSelected) Colors.White else Colors.White
    val textColorAlt = if (isSelected) Colors.White else Colors.White80

    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor, AppShapes.small)
            .clickableAlpha { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Caption(text = "$index:", color = textColorAlt)

        Caption(
            text = address,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.weight(1f)
        )

        CaptionB(text = balance.formatToModernDisplay(), color = textColorAlt)
    }
}

@Suppress("SpellCheckingInspection")
@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        AddressViewerContent(
            uiState = UiState(
                addresses = listOf(
                    AddressModel(
                        address = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh",
                        index = 0,
                        path = "m/84'/0'/0'/0/0"
                    ),
                    AddressModel(
                        address = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
                        index = 1,
                        path = "m/84'/0'/0'/0/1"
                    ),
                    AddressModel(
                        address = "bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3",
                        index = 2,
                        path = "m/84'/0'/0'/0/2"
                    ),
                    AddressModel(
                        address = "bc1q9vza2e8x573nczrlzms0wvx3gsqjx7vavgkx0l",
                        index = 3,
                        path = "m/84'/0'/0'/0/3"
                    ),
                    AddressModel(
                        address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq",
                        index = 4,
                        path = "m/84'/0'/0'/0/4"
                    ),
                ),
                balances = mapOf(
                    "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh" to 50000L,
                    "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4" to 0L,
                    "bc1qrp33g0q5c5txsp9arysrx4k6zdkfs4nce4xj0gdcccefvpysxf3qccfmv3" to 1500000L,
                    "bc1q9vza2e8x573nczrlzms0wvx3gsqjx7vavgkx0l" to 0L,
                    "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq" to 250000L,
                ),
                selectedAddress = AddressModel(
                    address = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh",
                    index = 0,
                    path = "m/84'/0'/0'/0/0"
                ),
                showReceiveAddresses = true,
            ),
        )
    }
}
