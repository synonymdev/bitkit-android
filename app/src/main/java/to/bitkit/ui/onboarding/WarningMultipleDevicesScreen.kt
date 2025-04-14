package to.bitkit.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WarningMultipleDevicesScreen(
    onBackClick: () -> Unit,
    onConfirmClick: () -> Unit,
) {

    Column(
        modifier = Modifier.fillMaxSize()
    ) {

        TopAppBar(
            title = {},
            navigationIcon = {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Default.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        modifier = Modifier.size(24.dp)
                    )
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.phone),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            Display(
                text = stringResource(R.string.onboarding__multiple_header).withAccent(accentColor = Colors.Yellow),
            )

            Spacer(modifier = Modifier.height(8.dp))

            BodyM(
                text = stringResource(R.string.onboarding__multiple_text),
                color = Colors.White64,
            )

            Spacer(modifier = Modifier.height(32.dp))

            PrimaryButton(
                text = stringResource(R.string.common__understood),
                onClick = onConfirmClick,
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}


@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        WarningMultipleDevicesScreen(
            onBackClick = {},
            onConfirmClick = {}
        )
    }
}
