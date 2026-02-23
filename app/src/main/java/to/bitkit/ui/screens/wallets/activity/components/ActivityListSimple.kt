package to.bitkit.ui.screens.wallets.activity.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.synonym.bitkitcore.Activity
import to.bitkit.R
import to.bitkit.ui.components.TertiaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.screens.wallets.activity.utils.previewActivityItems
import to.bitkit.ui.theme.AppThemeSurface

@Composable
fun ActivityListSimple(
    items: List<Activity>?,
    onAllActivityClick: () -> Unit,
    onActivityItemClick: (String) -> Unit,
) {
    if (items.isNullOrEmpty()) return
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        items.forEachIndexed { index, item ->
            ActivityRow(item, onActivityItemClick, testTag = "ActivityShort-$index")
            VerticalSpacer(16.dp)
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
