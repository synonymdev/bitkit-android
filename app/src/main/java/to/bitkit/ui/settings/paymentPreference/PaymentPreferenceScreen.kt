package to.bitkit.ui.settings.paymentPreference

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.settings.SectionHeader
import to.bitkit.ui.components.settings.SettingsSwitchRow
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun PaymentPreferenceScreen(
    onBack: () -> Unit,
    viewModel: PaymentPreferenceViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    PaymentPreferenceContent(
        uiState = uiState,
        onBack = onBack,
        onToggleLightning = { viewModel.setLightningEnabled(!uiState.lightningEnabled) },
        onToggleOnchain = { viewModel.setOnchainEnabled(!uiState.onchainEnabled) },
        onTogglePrivateContacts = { viewModel.setPrivateContactsEnabled(!uiState.privateContactsEnabled) },
        onTogglePublicContacts = { viewModel.setPublicContactsEnabled(!uiState.publicContactsEnabled) },
    )
}

@Composable
private fun PaymentPreferenceContent(
    uiState: PaymentPreferenceUiState,
    onBack: () -> Unit = {},
    onToggleLightning: () -> Unit = {},
    onToggleOnchain: () -> Unit = {},
    onTogglePrivateContacts: () -> Unit = {},
    onTogglePublicContacts: () -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.settings__payment_pref_title),
            onBackClick = onBack,
            actions = { DrawerNavIcon() },
        )

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .testTag("PaymentPreferenceScreen")
        ) {
            BodyM(
                text = stringResource(R.string.settings__payment_pref_header),
                color = Colors.White64,
                modifier = Modifier.padding(top = 32.dp, bottom = 16.dp)
            )

            SectionHeader(
                title = stringResource(R.string.settings__payment_pref_options),
                padding = PaddingValues.Zero,
            )

            SettingsSwitchRow(
                title = stringResource(R.string.settings__payment_pref_lightning),
                isChecked = uiState.lightningEnabled,
                onClick = onToggleLightning,
                enabled = !uiState.isUpdatingPaymentOptions && (!uiState.lightningEnabled || uiState.onchainEnabled),
                modifier = Modifier.testTag("PaymentPreferenceLightning")
            )
            SettingsSwitchRow(
                title = stringResource(R.string.settings__payment_pref_onchain),
                isChecked = uiState.onchainEnabled,
                onClick = onToggleOnchain,
                enabled = !uiState.isUpdatingPaymentOptions && (!uiState.onchainEnabled || uiState.lightningEnabled),
                modifier = Modifier.testTag("PaymentPreferenceOnchain")
            )

            if (uiState.hasPubkyProfile) {
                SectionHeader(
                    title = stringResource(R.string.settings__payment_pref_contacts),
                    padding = PaddingValues(top = 16.dp),
                )

                SettingsSwitchRow(
                    title = stringResource(R.string.settings__payment_pref_private_contacts),
                    isChecked = uiState.privateContactsEnabled,
                    onClick = onTogglePrivateContacts,
                    enabled = !uiState.isUpdatingPrivateContacts,
                    modifier = Modifier.testTag("PaymentPreferencePrivateContacts")
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.settings__payment_pref_public_contacts),
                    isChecked = uiState.publicContactsEnabled,
                    onClick = onTogglePublicContacts,
                    enabled = !uiState.isUpdatingPublicContacts,
                    modifier = Modifier.testTag("PaymentPreferencePublicContacts")
                )
            }

            VerticalSpacer(220.dp)
            if (uiState.hasPubkyProfile) {
                BodyS(
                    text = stringResource(R.string.settings__payment_pref_contacts_footer),
                    color = Colors.White64,
                )
            }
            VerticalSpacer(32.dp)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        PaymentPreferenceContent(
            uiState = PaymentPreferenceUiState(
                lightningEnabled = true,
                onchainEnabled = true,
                privateContactsEnabled = true,
                publicContactsEnabled = true,
                hasPubkyProfile = true,
            ),
        )
    }
}
