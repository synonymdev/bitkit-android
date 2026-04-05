package to.bitkit.ui.screens.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun PayContactsScreen(
    onContinue: () -> Unit,
    onBackClick: () -> Unit,
) {
    Content(
        onContinue = onContinue,
        onBackClick = onBackClick,
    )
}

@Composable
private fun Content(
    onContinue: () -> Unit,
    onBackClick: () -> Unit,
) {
    var isPaymentSharingEnabled by remember { mutableStateOf(true) }

    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.profile__pay_contacts_title),
            onBackClick = onBackClick,
            actions = { DrawerNavIcon() },
        )

        Column(
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.coin_stack),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            Display(
                text = stringResource(R.string.profile__pay_contacts_headline)
                    .withAccent(accentColor = Colors.PubkyGreen),
                color = Colors.White,
            )
            VerticalSpacer(16.dp)
            BodyM(
                text = stringResource(R.string.profile__pay_contacts_description),
                color = Colors.White64,
            )
            VerticalSpacer(24.dp)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                BodyM(
                    text = stringResource(R.string.profile__pay_contacts_toggle),
                    color = Colors.White,
                    modifier = Modifier.weight(1f)
                )
                HorizontalSpacer(16.dp)
                Switch(
                    checked = isPaymentSharingEnabled,
                    onCheckedChange = { isPaymentSharingEnabled = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Colors.White,
                        checkedTrackColor = Colors.PubkyGreen,
                        checkedBorderColor = Colors.PubkyGreen,
                        uncheckedThumbColor = Colors.White,
                        uncheckedTrackColor = Colors.Gray4,
                        uncheckedBorderColor = Colors.Gray4,
                    ),
                )
            }

            VerticalSpacer(32.dp)
            PrimaryButton(
                text = stringResource(R.string.common__continue),
                onClick = onContinue,
            )
            VerticalSpacer(16.dp)
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            onContinue = {},
            onBackClick = {},
        )
    }
}
