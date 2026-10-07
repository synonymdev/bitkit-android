package to.bitkit.ui.screens.wallets.activity.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.UsdtTransfer
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import to.bitkit.R
import to.bitkit.ext.scopedId
import to.bitkit.ext.timestamp
import to.bitkit.ui.activityListViewModel
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.TertiaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.screens.wallets.activity.utils.previewActivityItems
import to.bitkit.ui.screens.wallets.usdt.UsdtActivityRow
import to.bitkit.ui.settingsViewModel
import to.bitkit.ui.theme.AppThemeSurface

@Composable
fun ActivityListSimple(
    items: ImmutableList<Activity>?,
    onAllActivityClick: () -> Unit,
    onActivityItemClick: (Activity) -> Unit,
    hardwareIds: ImmutableSet<String> = persistentSetOf(),
    usdtItems: ImmutableList<UsdtTransfer> = persistentListOf(),
    maxItems: Int = 4,
) {
    if (items.isNullOrEmpty() && usdtItems.isEmpty()) return
    val app = appViewModel
    val settings = settingsViewModel
    val hideBalance by settings?.hideBalance?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(false) }
    val rows = remember(items, usdtItems, maxItems) {
        (items.orEmpty().map { WalletActivity.Bitcoin(it) } + usdtItems.map { WalletActivity.Usdt(it) })
            .sortedByDescending { it.timestamp }.take(maxItems)
    }

    val contacts by activityListViewModel?.contacts?.collectAsStateWithLifecycle() ?: remember {
        mutableStateOf(persistentListOf())
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        rows.forEachIndexed { index, entry ->
            when (entry) {
                is WalletActivity.Bitcoin -> ActivityRow(
                    item = entry.item,
                    onClick = onActivityItemClick,
                    testTag = "ActivityShort-$index",
                    title = contactActivityTitle(entry.item, contacts),
                    isHardware = entry.item.scopedId() in hardwareIds,
                    contact = contactForActivity(entry.item, contacts),
                )
                is WalletActivity.Usdt -> UsdtActivityRow(
                    entry.item,
                    hideBalance,
                    onClick = { app?.navigateToUsdtActivity(entry.item.id) }
                )
            }
            if (index < rows.lastIndex) VerticalSpacer(16.dp)
        }
        TertiaryButton(
            text = stringResource(R.string.wallet__activity_show_all),
            onClick = onAllActivityClick,
            modifier = Modifier
                .wrapContentWidth()
                .testTag("ActivityShowAll")
        )
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        ActivityListSimple(
            items = previewActivityItems,
            onAllActivityClick = {},
            onActivityItemClick = {},
        )
    }
}

@Preview
@Composable
private fun PreviewEmpty() {
    AppThemeSurface {
        ActivityListSimple(
            items = persistentListOf(),
            onAllActivityClick = {},
            onActivityItemClick = {},
        )
    }
}

internal sealed interface WalletActivity {
    val timestamp: ULong
    data class Bitcoin(val item: Activity) : WalletActivity { override val timestamp get() = item.timestamp() }
    data class Usdt(val item: UsdtTransfer) : WalletActivity { override val timestamp get() = item.timestamp }
}
