package to.bitkit.ui.settings.advanced

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.models.Toast
import to.bitkit.models.WatchOnlyAccountRecord
import to.bitkit.models.WatchOnlyAccountSetupState
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.TextInput
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
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
    accounts: List<WatchOnlyAccountRecord>,
    updatingAccountId: String?,
    onBack: () -> Unit,
    onRename: (WatchOnlyAccountRecord, String) -> Unit,
    onTrackingChange: (WatchOnlyAccountRecord, Boolean) -> Unit,
) {
    ScreenColumn(modifier = Modifier.testTag("WatchOnlyAccountsScreen")) {
        AppTopBar(
            titleText = stringResource(R.string.watch_only_accounts__title),
            onBackClick = onBack,
        )
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            item {
                BodyM(
                    text = stringResource(R.string.watch_only_accounts__description),
                    color = Colors.White64,
                )
            }
            if (accounts.isEmpty()) {
                item { EmptyState() }
            } else {
                items(accounts, key = WatchOnlyAccountRecord::id) { account ->
                    AccountCard(
                        account = account,
                        isUpdating = updatingAccountId != null,
                        onRename = onRename,
                        onTrackingChange = onTrackingChange,
                    )
                }
            }
            item { VerticalSpacer(32.dp) }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Colors.Gray6, RoundedCornerShape(16.dp))
            .padding(24.dp)
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

@Composable
private fun AccountCard(
    account: WatchOnlyAccountRecord,
    isUpdating: Boolean,
    onRename: (WatchOnlyAccountRecord, String) -> Unit,
    onTrackingChange: (WatchOnlyAccountRecord, Boolean) -> Unit,
) {
    var name by remember(account.id) { mutableStateOf(account.name) }
    LaunchedEffect(account.name) { name = account.name }
    val context = LocalContext.current
    val app = appViewModel
    val copyXpub = copyToClipboard(account.xpub) {
        app?.toast(
            type = Toast.ToastType.SUCCESS,
            title = context.getString(R.string.common__copied),
        )
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(Colors.Gray6, RoundedCornerShape(16.dp))
            .padding(20.dp)
            .testTag("WatchOnlyAccount_${account.accountIndex}"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                BodySSB(account.name)
                BodyM(account.derivationPath, color = Colors.White64)
            }
            Switch(
                checked = account.isTrackingEnabled,
                onCheckedChange = { onTrackingChange(account, it) },
                enabled = !isUpdating,
                modifier = Modifier.testTag("WatchOnlyAccountTracking_${account.accountIndex}"),
            )
        }

        Column {
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
        }

        Column {
            Caption13Up(stringResource(R.string.watch_only_accounts__xpub), color = Colors.White64)
            VerticalSpacer(8.dp)
            BodyM(
                text = account.xpub,
                color = Colors.White64,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("WatchOnlyAccountXpub_${account.accountIndex}"),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton(
                text = stringResource(R.string.watch_only_accounts__save_name),
                onClick = { onRename(account, name) },
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

        if (account.setupState == WatchOnlyAccountSetupState.PendingDelivery) {
            BodyM(
                text = stringResource(R.string.watch_only_accounts__pending_delivery),
                color = Colors.Yellow,
                modifier = Modifier.testTag("WatchOnlyAccountPending_${account.accountIndex}"),
            )
        }
    }
}
