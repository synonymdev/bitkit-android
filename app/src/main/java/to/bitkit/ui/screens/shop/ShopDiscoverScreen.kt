package to.bitkit.ui.screens.shop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.models.BitrefillCategory
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun ShopDiscoverScreen(
    onClose: () -> Unit,
    onBack: () -> Unit,
) {
    ScreenColumn(
        modifier = Modifier.gradientBackground()
    ) {
        AppTopBar(
            titleText = stringResource(R.string.other__shop__discover__nav_title),
            onBackClick = onBack,
            actions = { CloseNavIcon(onClick = onClose) },
        )
        // TODO ADD TabBar

        LazyColumn {
            items(items = BitrefillCategory.entries.toList(), key = { it.name }) { item ->
                Column {
                    Row(
                        modifier = Modifier.padding(top = 8.5.dp, bottom = 10.5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .size(32.dp)
                                .background(Colors.White10),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = null,
                                tint = Colors.White64,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        BodyM(text = item.title, modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp))
                        Icon(
                            painter = painterResource(R.drawable.ic_chevron_right),
                            contentDescription = null,
                            tint = Colors.White64,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        ShopDiscoverScreen(onClose = {}, onBack = {})
    }
}
