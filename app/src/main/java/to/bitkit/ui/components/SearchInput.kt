package to.bitkit.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.bitkit.R
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.theme.AppTextFieldDefaults
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.theme.InterFontFamily

@Composable
fun SearchInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = stringResource(R.string.common__search),
    trailingContent: @Composable (RowScope.() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(Colors.White10, MaterialTheme.shapes.large)
            .padding(horizontal = 16.dp)
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_magnifying_glass),
            contentDescription = null,
            tint = if (value.isNotEmpty()) Colors.Brand else Colors.White64,
            modifier = Modifier.size(24.dp)
        )

        TextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = LocalTextStyle.current.merge(searchTextStyle),
            placeholder = {
                Text(
                    text = placeholder,
                    style = LocalTextStyle.current.merge(searchTextStyle),
                )
            },
            colors = AppTextFieldDefaults.transparent,
            singleLine = true,
            modifier = Modifier.weight(1f)
        )

        if (trailingContent != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                trailingContent()
            }
        }
    }
}

@Composable
fun SearchInputIconButton(
    iconRes: Int,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Icon(
        painter = painterResource(iconRes),
        contentDescription = contentDescription,
        tint = if (isActive) Colors.Brand else Colors.White64,
        modifier = modifier
            .size(24.dp)
            .clickableAlpha(onClick = onClick)
    )
}

private val searchTextStyle = TextStyle(
    fontWeight = FontWeight.Normal,
    fontSize = 17.sp,
    letterSpacing = 0.4.sp,
    fontFamily = InterFontFamily,
    textAlign = TextAlign.Start,
)

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        Box(modifier = Modifier.padding(16.dp)) {
            SearchInput(
                value = "",
                onValueChange = {},
            )
        }
    }
}

@Preview
@Composable
private fun PreviewWithValue() {
    AppThemeSurface {
        Box(modifier = Modifier.padding(16.dp)) {
            SearchInput(
                value = "USD",
                onValueChange = {},
            )
        }
    }
}

@Preview
@Composable
private fun PreviewWithTrailingIcons() {
    AppThemeSurface {
        Box(modifier = Modifier.padding(16.dp)) {
            SearchInput(
                value = "BTC",
                onValueChange = {},
                trailingContent = {
                    SearchInputIconButton(
                        iconRes = R.drawable.ic_tag,
                        isActive = true,
                        onClick = {}
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    SearchInputIconButton(
                        iconRes = R.drawable.ic_calendar,
                        isActive = false,
                        onClick = {}
                    )
                }
            )
        }
    }
}
