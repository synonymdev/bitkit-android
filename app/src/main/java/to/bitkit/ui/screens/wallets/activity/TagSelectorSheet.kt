package to.bitkit.ui.screens.wallets.activity

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.ActivityListViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagSelectorSheet(
    viewModel: ActivityListViewModel,
    onClearClick: () -> Unit,
    onApplyClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(.875f)
            .padding(horizontal = 16.dp)
    ) {
        SheetTopBar(stringResource(R.string.wallet__tags_filter_title))

        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(align = Alignment.Top),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val availableTags by viewModel.availableTags.collectAsState()
            val selectedTags by viewModel.selectedTags.collectAsState()

            availableTags.forEach { tag ->
                TagButton(
                    text = tag,
                    isSelected = selectedTags.contains(tag),
                    onClick = { viewModel.toggleTag(tag) }
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .padding(vertical = 16.dp)
                .fillMaxWidth(),
        ) {
            SecondaryButton(
                onClick = onClearClick,
                text = "Clear",
                modifier = Modifier.weight(1f),
            )
            PrimaryButton(
                onClick = onApplyClick,
                text = "Apply",
                modifier = Modifier.weight(1f),
            )
        }
    }

}

@Composable
fun TagButton(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (isSelected) Colors.Brand else Colors.White16
    val textColor = if (isSelected) Colors.Brand else MaterialTheme.colorScheme.onSurface

    Text(
        text = text,
        color = textColor,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .wrapContentWidth()
            .border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}
