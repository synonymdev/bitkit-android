package to.bitkit.ui.settings.advanced

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import kotlinx.collections.immutable.ImmutableList
import to.bitkit.R
import to.bitkit.models.Toast
import to.bitkit.models.WatchOnlyAccountRecord
import to.bitkit.models.WatchOnlyAccountSetupState
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.BottomSheet
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.TextInput
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.settings.SectionHeader
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.components.settings.SettingsSwitchRow
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.copyToClipboard

@Composable
fun WatchOnlyAccountsScreen(
    navController: NavController,
    viewModel: WatchOnlyAccountsViewModel = hiltViewModel(),
) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val updatingAccountId by viewModel.updatingAccountId.collectAsStateWithLifecycle()

    Content(
        accounts = accounts,
        updatingAccountId = updatingAccountId,
        onBack = { navController.popBackStack() },
        onRename = viewModel::rename,
        onTrackingChange = viewModel::setTrackingEnabled,
    )
}

@Composable
private fun Content(
    accounts: ImmutableList<WatchOnlyAccountRecord>,
    updatingAccountId: String?,
    onBack: () -> Unit,
    onRename: (WatchOnlyAccountRecord, String) -> Unit,
    onTrackingChange: (WatchOnlyAccountRecord, Boolean) -> Unit,
) {
    val activeAccounts = accounts.filter { it.setupState == WatchOnlyAccountSetupState.Active }
    val pendingAccounts = accounts.filter { it.setupState != WatchOnlyAccountSetupState.Active }
    var selectedAccount by remember { mutableStateOf<WatchOnlyAccountRecord?>(null) }

    ScreenColumn(modifier = Modifier.testTag("WatchOnlyAccountsScreen")) {
        AppTopBar(
            titleText = stringResource(R.string.watch_only_accounts__title),
            onBackClick = onBack,
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            item {
                BodyM(
                    text = stringResource(R.string.watch_only_accounts__description),
                    color = Colors.White64,
                )
                VerticalSpacer(8.dp)
            }
            if (accounts.isEmpty()) {
                item { EmptyState() }
            } else {
                if (activeAccounts.isNotEmpty()) {
                    item {
                        SectionHeader(stringResource(R.string.watch_only_accounts__active_section))
                    }
                    items(activeAccounts, key = WatchOnlyAccountRecord::id) { account ->
                        ActiveAccountRows(
                            account = account,
                            isUpdating = updatingAccountId != null,
                            onOpenDetails = { selectedAccount = account },
                            onTrackingChange = onTrackingChange,
                        )
                    }
                }

                if (pendingAccounts.isNotEmpty()) {
                    item {
                        SectionHeader(
                            title = stringResource(R.string.watch_only_accounts__pending_section),
                            color = Colors.Yellow,
                        )
                        BodyM(
                            text = stringResource(R.string.watch_only_accounts__pending_description),
                            color = Colors.White64,
                        )
                        VerticalSpacer(8.dp)
                    }
                    items(pendingAccounts, key = WatchOnlyAccountRecord::id) { account ->
                        PendingAccountRow(
                            account = account,
                            onOpenDetails = { selectedAccount = account },
                        )
                    }
                }
            }
            item { VerticalSpacer(32.dp) }
        }
    }

    selectedAccount?.let { account ->
        AccountDetailsSheet(
            account = account,
            onRename = { name -> onRename(account, name) },
            onDismiss = { selectedAccount = null },
        )
    }
}

@Composable
private fun ActiveAccountRows(
    account: WatchOnlyAccountRecord,
    isUpdating: Boolean,
    onOpenDetails: () -> Unit,
    onTrackingChange: (WatchOnlyAccountRecord, Boolean) -> Unit,
) {
    Column(modifier = Modifier.testTag("WatchOnlyAccount_${account.accountIndex}")) {
        SettingsButtonRow(
            title = account.name,
            subtitle = account.derivationPath,
            onClick = onOpenDetails,
        )
        SettingsSwitchRow(
            title = stringResource(R.string.watch_only_accounts__tracking),
            isChecked = account.isTrackingEnabled,
            onClick = { onTrackingChange(account, !account.isTrackingEnabled) },
            enabled = !isUpdating,
            switchTestTag = "WatchOnlyAccountTracking_${account.accountIndex}",
        )
        VerticalSpacer(8.dp)
    }
}

@Composable
private fun PendingAccountRow(
    account: WatchOnlyAccountRecord,
    onOpenDetails: () -> Unit,
) {
    SettingsButtonRow(
        title = account.name,
        subtitle = account.derivationPath,
        description = stringResource(R.string.watch_only_accounts__setup_not_confirmed),
        onClick = onOpenDetails,
        modifier = Modifier.testTag("WatchOnlyAccount_${account.accountIndex}"),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountDetailsSheet(
    account: WatchOnlyAccountRecord,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(account.id, account.name) { mutableStateOf(account.name) }
    val context = LocalContext.current
    val app = appViewModel
    val copyXpub = copyToClipboard(account.xpub) {
        app?.toast(
            type = Toast.ToastType.SUCCESS,
            title = context.getString(R.string.common__copied),
        )
    }

    BottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.imePadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .testTag("WatchOnlyAccountDetails_${account.accountIndex}"),
        ) {
            SheetTopBar(titleText = stringResource(R.string.watch_only_accounts__details_title))

            if (account.setupState != WatchOnlyAccountSetupState.Active) {
                BodyM(
                    text = stringResource(R.string.watch_only_accounts__setup_not_finished),
                    color = Colors.Yellow,
                    modifier = Modifier.testTag("WatchOnlyAccountPending_${account.accountIndex}"),
                )
                VerticalSpacer(24.dp)
            }

            Caption13Up(stringResource(R.string.watch_only_accounts__name), color = Colors.White64)
            VerticalSpacer(8.dp)
            TextInput(
                value = name,
                onValueChange = { name = it },
                placeholder = stringResource(R.string.watch_only_accounts__name_placeholder),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("WatchOnlyAccountName_${account.accountIndex}"),
            )

            VerticalSpacer(24.dp)
            Caption13Up(stringResource(R.string.watch_only_accounts__xpub), color = Colors.White64)
            VerticalSpacer(8.dp)
            BodyM(
                text = account.xpub,
                color = Colors.White64,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("WatchOnlyAccountXpub_${account.accountIndex}"),
            )

            VerticalSpacer(24.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(
                    text = stringResource(R.string.watch_only_accounts__save_name),
                    onClick = { onRename(name) },
                    size = ButtonSize.Small,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("WatchOnlyAccountSaveName_${account.accountIndex}"),
                )
                SecondaryButton(
                    text = stringResource(R.string.watch_only_accounts__copy_xpub),
                    onClick = copyXpub,
                    size = ButtonSize.Small,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("WatchOnlyAccountCopyXpub_${account.accountIndex}"),
                )
            }
            VerticalSpacer(24.dp)
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp)
            .testTag("WatchOnlyAccountsEmpty"),
    ) {
        BodySSB(stringResource(R.string.watch_only_accounts__empty_title))
        VerticalSpacer(8.dp)
        BodyM(
            text = stringResource(R.string.watch_only_accounts__empty_description),
            color = Colors.White64,
        )
    }
}
