package to.bitkit.ui.screens.widgets

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun DragAndDropWidget(
    @DrawableRes iconRes: Int,
    title: String,
    onClickDelete: () -> Unit,
    onClickSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(shape = MaterialTheme.shapes.medium)
            .background(Colors.White10)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier
                    .size(32.dp)
                    .testTag("${title}_drag_and_drop_icon"),
                tint = Color.Unspecified
            )

            BodyMSB(
                text = title,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp)
                    .testTag("${title}_drag_and_drop_title")
            )

            IconButton(
                onClick = onClickDelete,
                modifier = Modifier.testTag("${title}_drag_and_drop_delete")
            ) {
                Icon(
                    modifier = Modifier.size(24.dp),
                    painter = painterResource(R.drawable.ic_trash),
                    contentDescription = stringResource(R.string.common__delete)
                )
            }

            IconButton(
                onClick = onClickSettings,
                modifier = Modifier.testTag("${title}_drag_and_drop_edit")
            ) {
                Icon(
                    modifier = Modifier.size(24.dp),
                    painter = painterResource(R.drawable.ic_settings),
                    contentDescription = stringResource(R.string.common__edit)
                )
            }

            IconButton(
                onClick = onClickDelete,
                modifier = Modifier.testTag("${title}_drag_and_drop_move")
            ) {
                Icon(
                    modifier = Modifier.size(24.dp),
                    painter = painterResource(R.drawable.ic_list),
                    contentDescription = null
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        DragAndDropWidget(
            modifier = Modifier.padding(16.dp),
            iconRes = R.drawable.widget_cube,
            title = stringResource(R.string.widgets__blocks__name),
            onClickDelete = {},
            onClickSettings = {}
        )
    }
}
