package to.bitkit.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.bitkit.R
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.theme.InterFontFamily

@Composable
fun DrawerItem(
    label: String,
    @DrawableRes iconRes: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(NavigationDrawerItemDefaults.ItemPadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {

        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(24.dp)
        )

        Text(
            text = label,
            style = TextStyle(
                fontWeight = FontWeight.Black,
                fontSize = 24.sp,
                lineHeight = 24.sp,
                letterSpacing = (-1).sp,
                fontFamily = InterFontFamily,
                color = Colors.White,
            ),
            modifier = Modifier.weight(1f)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Column(
            modifier = Modifier.width(200.dp).fillMaxHeight().background(color = Colors.Brand)
        ) {
            VerticalSpacer(60.dp)

            DrawerItem(
                label = stringResource(R.string.wallet__drawer__wallet),
                iconRes = R.drawable.ic_coins
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            DrawerItem(
                label = stringResource(R.string.wallet__drawer__activity),
                iconRes = R.drawable.ic_heartbeat
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            DrawerItem(
                label = stringResource(R.string.wallet__drawer__contacts),
                iconRes = R.drawable.ic_users
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            DrawerItem(
                label = stringResource(R.string.wallet__drawer__profile),
                iconRes = R.drawable.ic_user_square
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            DrawerItem(
                label = stringResource(R.string.wallet__drawer__widgets),
                iconRes = R.drawable.ic_stack
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            DrawerItem(
                label = stringResource(R.string.wallet__drawer__widgets),
                iconRes = R.drawable.ic_store_front
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            DrawerItem(
                label = stringResource(R.string.wallet__drawer__settings),
                iconRes = R.drawable.ic_settings
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        }
    }

}
