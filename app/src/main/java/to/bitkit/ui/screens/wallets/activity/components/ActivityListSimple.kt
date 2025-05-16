package to.bitkit.ui.screens.wallets.activity.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.TertiaryButton
import to.bitkit.ui.screens.wallets.activity.utils.previewActivityItems
import to.bitkit.ui.theme.AppThemeSurface
import uniffi.bitkitcore.Activity

@Composable
fun ActivityListSimple(
    items: List<Activity>?,
    onAllActivityClick: () -> Unit,
    onActivityItemClick: (String) -> Unit,
    onEmptyActivityRowClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (items != null && items.isNotEmpty()) {
            items.forEach { item ->
                ActivityRow(item, onActivityItemClick)
                HorizontalDivider()
            }
            TertiaryButton(
                text = stringResource(R.string.wallet__activity_show_all),
                onClick = onAllActivityClick,
                modifier = Modifier
                    .wrapContentWidth()
                    .padding(top = 8.dp)
            )
        } else {
            EmptyActivityRow(onClick = onEmptyActivityRowClick)
        }
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
            onEmptyActivityRowClick = {},
        )
    }
}

@Preview
@Composable
private fun PreviewEmpty() {
    AppThemeSurface {
        ActivityListSimple(
            items = emptyList(),
            onAllActivityClick = {},
            onActivityItemClick = {},
            onEmptyActivityRowClick = {},
        )
    }
}
