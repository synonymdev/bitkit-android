package to.bitkit.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun EmptyStateView(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
) {
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .padding(bottom = 130.dp)
        ) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Display(
                    text = text,
                    modifier = Modifier.width(220.dp)
                )
                Image(
                    painter = painterResource(id = R.drawable.empty_state_arrow),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .heightIn(max = 144.dp)
                        .offset(x = (-10).dp)
                )
                Spacer(modifier = Modifier.weight(1f))
            }
        }
        if (onClose != null) {
            IconButton(
                onClick = {
                    onClose()
                },
                modifier = Modifier
                    .size(40.dp)
                    .align(Alignment.TopEnd)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = Colors.White64,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun EmptyStateViewPreview() {
    AppThemeSurface {
        EmptyStateView(
            text = stringResource(R.string.onboarding__empty_wallet).withAccent(),
            onClose = {},
        )
    }
}
