package to.bitkit.ui.shared

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun TabBar(
    onSendClick: () -> Unit,
    onReceiveClick: () -> Unit,
    onScanClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val buttonColors = ButtonDefaults.buttonColors(
        containerColor = Color(40, 40, 40, (0.95f * 255).toInt()),
        contentColor = MaterialTheme.colorScheme.onSurface,
    )
    val contentPadding = PaddingValues(0.dp, 18.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp)
    ) {
        Row {
            val iconToTextSpace = 4.dp
            val iconSize = 20.dp
            Button(
                onClick = onSendClick,
                shape = RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50),
                colors = buttonColors,
                contentPadding = contentPadding,
                elevation = null,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowUpward,
                    contentDescription = stringResource(R.string.wallet__send),
                    modifier = Modifier.size(iconSize)
                )
                Spacer(Modifier.width(iconToTextSpace))
                Text(text = stringResource(R.string.wallet__send))
            }
            Button(
                onClick = onReceiveClick,
                shape = RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50),
                colors = buttonColors,
                contentPadding = contentPadding,
                elevation = null,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowDownward,
                    contentDescription = stringResource(R.string.wallet__receive),
                    modifier = Modifier.size(iconSize)
                )
                Spacer(Modifier.width(iconToTextSpace))
                Text(text = stringResource(R.string.wallet__receive))
            }
        }
        Button(
            onClick = onScanClick,
            shape = MaterialTheme.shapes.extraLarge,
            colors = ButtonDefaults.buttonColors(containerColor = Colors.Gray6),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier
                .size(80.dp)
                .border(2.dp, buttonColors.containerColor, MaterialTheme.shapes.extraLarge),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_scan),
                contentDescription = stringResource(R.string.wallet__recipient_scan),
                tint = Colors.Gray2,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Preview
@Composable
private fun TabBarPreview() {
    AppThemeSurface {
        TabBar(
            onSendClick = {},
            onReceiveClick = {},
            onScanClick = {},
        )
    }
}
