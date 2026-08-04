package to.bitkit.ui.screens.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Display
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
    viewModel: PayContactsViewModel,
    onContinue: () -> Unit,
    onBackClick: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.effects.collect {
            when (it) {
                PayContactsEffect.Continue -> onContinue()
            }
        }
    }

    Content(
        uiState = uiState,
        onContinue = { viewModel.continueToProfile() },
        onBackClick = onBackClick,
    )
}

@Composable
private fun Content(
    uiState: PayContactsUiState,
    onContinue: () -> Unit,
    onBackClick: () -> Unit,
) {
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
            VerticalSpacer(32.dp)
            PrimaryButton(
                text = stringResource(R.string.common__continue),
                onClick = onContinue,
                enabled = !uiState.isLoading,
                modifier = Modifier.testTag("PayContactsContinue")
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
            uiState = PayContactsUiState(),
            onContinue = {},
            onBackClick = {},
        )
    }
}
