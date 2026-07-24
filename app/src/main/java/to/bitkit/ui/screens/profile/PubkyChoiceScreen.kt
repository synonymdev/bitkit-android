package to.bitkit.ui.screens.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.persistentListOf
import to.bitkit.R
import to.bitkit.data.sharing.SharedPubkyContract
import to.bitkit.models.PubkyProfile
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.GradientCircularProgressIndicator
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.PubkyContactAvatar
import to.bitkit.ui.components.Text13Up
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.shared.util.screen
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

private const val TAG_IMAGE_WIDTH_FRACTION = 0.64f
private const val KEYRING_IMAGE_WIDTH_FRACTION = 0.83f
private const val TAG_OFFSET_X = -0.313f
private const val TAG_OFFSET_Y = 0.336f
private const val KEYRING_OFFSET_X = 0.251f
private const val KEYRING_OFFSET_Y = 0.27f
private const val TAG_ALPHA = 1f
private const val KEYRING_ALPHA = 0.9f

@Composable
fun PubkyChoiceScreen(
    viewModel: PubkyChoiceViewModel,
    onNavigateToCreateProfile: () -> Unit,
    onNavigateToContactImportOverview: () -> Unit,
    onNavigateToPayContacts: () -> Unit,
    onBackClick: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.effects.collect {
            when (it) {
                PubkyChoiceEffect.NavigateToContactImportOverview -> onNavigateToContactImportOverview()
                PubkyChoiceEffect.NavigateToPayContacts -> onNavigateToPayContacts()
            }
        }
    }

    Content(
        uiState = uiState,
        onBackClick = onBackClick,
        onCreateProfile = onNavigateToCreateProfile,
        onSelectRingIdentity = viewModel::selectRingIdentity,
    )
}

@Composable
private fun Content(
    uiState: PubkyChoiceUiState,
    onBackClick: () -> Unit,
    onCreateProfile: () -> Unit,
    onSelectRingIdentity: (SharedPubkyChoice) -> Unit,
) {
    Box(
        modifier = Modifier
            .screen()
            .clipToBounds()
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(R.drawable.tag_pubky),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth(TAG_IMAGE_WIDTH_FRACTION)
                    .align(Alignment.Center)
                    .offset(x = maxWidth * TAG_OFFSET_X, y = maxHeight * TAG_OFFSET_Y)
                    .alpha(TAG_ALPHA)
            )

            Image(
                painter = painterResource(R.drawable.keyring),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth(KEYRING_IMAGE_WIDTH_FRACTION)
                    .align(Alignment.Center)
                    .offset(x = maxWidth * KEYRING_OFFSET_X, y = maxHeight * KEYRING_OFFSET_Y)
                    .alpha(KEYRING_ALPHA)
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            AppTopBar(
                titleText = stringResource(R.string.profile__nav_title),
                onBackClick = onBackClick,
                actions = { DrawerNavIcon() },
                modifier = Modifier.offset(y = (-10).dp),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(y = (-6.5).dp)
                ) {
                    Display(
                        text = stringResource(R.string.profile__choice_title)
                            .withAccent(accentColor = Colors.PubkyGreen),
                        color = Colors.White,
                    )
                    VerticalSpacer(4.dp)
                    BodyM(
                        text = stringResource(R.string.profile__choice_description),
                        color = Colors.White64,
                        letterSpacing = 0.sp,
                    )
                    VerticalSpacer(33.dp)

                    CreateProfileCard(
                        enabled = uiState.selectedPubky == null,
                        onClick = onCreateProfile,
                    )

                    uiState.identities.forEach {
                        VerticalSpacer(8.dp)
                        RingIdentityCard(
                            choice = it,
                            isLoading = uiState.selectedPubky == it.pubky,
                            enabled = uiState.selectedPubky == null,
                            onClick = { onSelectRingIdentity(it) },
                        )
                    }

                    if (uiState.isDiscovering) {
                        VerticalSpacer(20.dp)
                        GradientCircularProgressIndicator(
                            modifier = Modifier
                                .size(24.dp)
                                .align(Alignment.CenterHorizontally)
                                .testTag("PubkyChoiceDiscovering")
                        )
                    }
                    VerticalSpacer(32.dp)
                }
            }
        }
    }
}

@Composable
private fun CreateProfileCard(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ChoiceCard(
        enabled = enabled,
        onClick = onClick,
        leading = {
            ChoiceIcon(iconResId = R.drawable.ic_user_plus)
        },
        content = {
            Text13Up(
                text = stringResource(R.string.profile__choice_new_pubky),
                color = Colors.White64,
            )
            BodyMSB(
                text = stringResource(R.string.profile__choice_create),
                color = Colors.White,
            )
        },
        modifier = Modifier.testTag("PubkyChoiceCreate")
    )
}

@Composable
private fun RingIdentityCard(
    choice: SharedPubkyChoice,
    isLoading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ChoiceCard(
        enabled = enabled,
        onClick = onClick,
        leading = {
            if (isLoading) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(40.dp)
                ) {
                    GradientCircularProgressIndicator(modifier = Modifier.size(20.dp))
                }
            } else {
                ChoiceIcon(iconResId = R.drawable.ic_key)
            }
        },
        content = {
            Text13Up(
                text = choice.profile.truncatedPublicKey,
                color = Colors.White64,
            )
            BodyMSB(
                text = choice.profile.name,
                color = Colors.White,
            )
        },
        trailing = {
            PubkyContactAvatar(
                profile = choice.profile,
                size = 32.dp,
                testTag = "PubkyChoiceRingAvatar",
            )
        },
        modifier = Modifier.testTag("PubkyChoiceRing_${choice.pubky}")
    )
}

@Composable
private fun ChoiceCard(
    leading: @Composable () -> Unit,
    content: @Composable () -> Unit,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Colors.Gray6)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(16.dp)
    ) {
        leading()
        HorizontalSpacer(16.dp)
        Column(modifier = Modifier.weight(1f)) {
            content()
        }
        trailing?.let {
            HorizontalSpacer(12.dp)
            it()
        }
    }
}

@Composable
private fun ChoiceIcon(iconResId: Int) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .background(Colors.Black, CircleShape)
    ) {
        Icon(
            painter = painterResource(iconResId),
            contentDescription = null,
            tint = Colors.PubkyGreen,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    val pubky = "3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
    AppThemeSurface {
        Content(
            uiState = PubkyChoiceUiState(
                identities = persistentListOf(
                    SharedPubkyChoice(
                        protocolVersion = SharedPubkyContract.PROTOCOL_VERSION,
                        sourcePackage = SharedPubkyContract.RING_SOURCE,
                        pubky = pubky,
                        profile = PubkyProfile.forDisplay(
                            publicKey = SharedPubkyContract.toBitkitPubky(pubky),
                            name = "Satoshi Nakamoto",
                            imageUrl = null,
                        ),
                    ),
                ),
            ),
            onBackClick = {},
            onCreateProfile = {},
            onSelectRingIdentity = {},
        )
    }
}
