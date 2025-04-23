package to.bitkit.ui.settings.pin

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.ui.Routes
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun ChangePinResultScreen(
    navController: NavController,
) {
    ChangePinResultContent(
        onOkClick = {
            navController.popBackStack<Routes.SecuritySettings>(inclusive = false)
        },
    )
}

@Composable
private fun ChangePinResultContent(
    onOkClick: () -> Unit,
) {
    ScreenColumn {
        AppTopBar(stringResource(R.string.security__cp_changed_title), onBackClick = null)
        Column(
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            BodyM(
                text = stringResource(R.string.security__cp_changed_text),
                color = Colors.White64,
            )

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Image(
                    painter = painterResource(R.drawable.check),
                    contentDescription = null,
                    modifier = Modifier.size(256.dp)
                )
            }

            PrimaryButton(
                text = stringResource(R.string.common__ok),
                onClick = onOkClick,
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        ChangePinResultContent(
            onOkClick = {},
        )
    }
}
