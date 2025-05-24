package to.bitkit.ui.settings.backups

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent


@Composable
fun BackupIntroScreen(
    hasFunds: Boolean,
    onClose: () -> Unit,
    onConfirm: () -> Unit,
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.security__backup_wallet),
            onBackClick = null,
        )

        Column(
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.safe),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            Display(
                text = stringResource(R.string.security__backup_title).withAccent(accentColor = Colors.Blue),
                color = Colors.White
            )
            Spacer(Modifier.height(8.dp))
            BodyM(
                text = if (hasFunds) {
                    stringResource(R.string.security__backup_funds)
                } else {
                    stringResource(R.string.security__backup_funds_no)
                },
                color = Colors.White64
            )
            Spacer(Modifier.height(32.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                SecondaryButton(
                    text = stringResource(R.string.common__later),
                    fullWidth = false,
                    onClick = onClose,
                    modifier = Modifier.weight(1f)
                )

                PrimaryButton(
                    text = stringResource(R.string.security__backup_button),
                    fullWidth = false,
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Preview(showBackground = true, name = "has funds")
@Composable
private fun Preview() {
    AppThemeSurface {
        BackupIntroScreen(
            onClose = {},
            onConfirm = {},
            hasFunds = true
        )
    }
}

@Preview(showBackground = true, name = "no funds")
@Composable
private fun Preview2() {
    AppThemeSurface {
        BackupIntroScreen(
            onClose = {},
            onConfirm = {},
            hasFunds = false
        )
    }
}
