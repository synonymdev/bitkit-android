package to.bitkit.ui.screens.wallets.activity.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import to.bitkit.R
import to.bitkit.ext.rawId
import to.bitkit.ui.activityListViewModel
import to.bitkit.ui.components.TertiaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.screens.wallets.activity.utils.previewActivityItems
import to.bitkit.ui.theme.AppThemeSurface

@Composable
fun ActivityListSimple(
    items: ImmutableList<Activity>?,
    onAllActivityClick: () -> Unit,
    onActivityItemClick: (String) -> Unit,
    hardwareIds: ImmutableSet<String> = persistentSetOf(),
) {
    if (items.isNullOrEmpty()) return

    val contacts by activityListViewModel?.contacts?.collectAsStateWithLifecycle() ?: remember {
        mutableStateOf(persistentListOf())
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        items.forEachIndexed { index, item ->
            ActivityRow(
                item = item,
                onClick = onActivityItemClick,
                testTag = "ActivityShort-$index",
                title = contactActivityTitle(item, contacts),
                isHardware = item.rawId() in hardwareIds,
                contact = contactForActivity(item, contacts),
            )
            if (index < items.lastIndex) {
                VerticalSpacer(16.dp)
            }
        }
        TertiaryButton(
            text = stringResource(R.string.wallet__activity_show_all),
            onClick = onAllActivityClick,
            modifier = Modifier
                .wrapContentWidth()
                .padding(top = 16.dp)
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
