package to.bitkit.ui.screens.wallets.send

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import to.bitkit.R
import to.bitkit.models.PubkyProfile
import to.bitkit.repositories.PrivatePaykitPaymentContext
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.GradientCircularProgressIndicator
import to.bitkit.ui.components.PubkyContactAvatar
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun SendContactSelectScreen(
    viewModel: SendContactSelectViewModel,
    onBack: () -> Unit,
    onOpenPayment: (String, String, PrivatePaykitPaymentContext?) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.effects.collect {
            when (it) {
                is SendContactSelectEffect.OpenPayment ->
                    onOpenPayment(it.paymentRequest, it.publicKey, it.privatePaymentContext)
            }
        }
    }

    SendContactSelectContent(
        uiState = uiState,
        onBack = onBack,
        onContactClick = viewModel::payContact,
    )
}

@Composable
private fun SendContactSelectContent(
    uiState: SendContactSelectUiState,
    onBack: () -> Unit = {},
    onContactClick: (String) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .testTag("SendContactSelectScreen")
    ) {
        SheetTopBar(
            titleText = stringResource(R.string.wallet__send_contact_title),
            onBack = onBack,
        )

        when {
            uiState.isLoading && uiState.contacts.isEmpty() -> {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    GradientCircularProgressIndicator()
                }
            }

            uiState.contacts.isEmpty() -> {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp)
                ) {
                    BodyM(
                        text = stringResource(R.string.wallet__send_contact_empty),
                        color = Colors.White64,
                    )
                }
            }

            else -> SendContactList(
                contacts = uiState.contacts,
                onContactClick = onContactClick,
            )
        }
    }
}

@Composable
private fun SendContactList(
    contacts: ImmutableList<PubkyProfile>,
    onContactClick: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        items(contacts, key = { it.publicKey }) { contact ->
            ContactRow(
                profile = contact,
                onClick = { onContactClick(contact.publicKey) },
                modifier = Modifier.testTag("SendContact_${contact.publicKey}")
            )
            HorizontalDivider(color = Colors.White10)
        }
    }
}

@Composable
private fun ContactRow(
    profile: PubkyProfile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickableAlpha(onClick = onClick)
            .padding(vertical = 12.dp)
    ) {
        PubkyContactAvatar(profile = profile)
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f)
        ) {
            BodyS(
                text = profile.truncatedPublicKey,
                color = Colors.White64,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BodySSB(
                text = profile.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        SendContactSelectContent(
            uiState = SendContactSelectUiState(
                contacts = persistentListOf(PubkyProfile.placeholder("pubky1")),
            ),
        )
    }
}
