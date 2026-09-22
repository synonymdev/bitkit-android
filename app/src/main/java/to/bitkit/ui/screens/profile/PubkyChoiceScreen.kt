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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.collections.immutable.persistentListOf
import to.bitkit.R
import to.bitkit.models.PubkyProfile
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.GradientCircularProgressIndicator
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.PubkyContactAvatar
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.shared.util.screen
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

private const val BG_IMAGE_WIDTH_FRACTION = 0.83f
private const val TAG_OFFSET_X = -0.179f
private const val TAG_OFFSET_Y = 0.13f
private const val KEYRING_OFFSET_X = 0.341f
private const val KEYRING_OFFSET_Y = 0.06f
private const val TAG_ALPHA = 0.6f
private const val KEYRING_ALPHA = 0.9f

@Composable
fun PubkyChoiceScreen(
    viewModel: PubkyChoiceViewModel,
    onNavigateToCreateProfile: () -> Unit,
    onNavigateToContactImportOverview: () -> Unit,
    onNavigateToPayContacts: () -> Unit,
    onNavigateToProfile: () -> Unit,
    onBackClick: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.effects.collect {
            when (it) {
                PubkyChoiceEffect.NavigateToCreateProfile -> onNavigateToCreateProfile()
                PubkyChoiceEffect.NavigateToContactImportOverview -> onNavigateToContactImportOverview()
                PubkyChoiceEffect.NavigateToPayContacts -> onNavigateToPayContacts()
            }
        }
    }

    LaunchedEffect(uiState.navigateToProfile) {
        if (!uiState.navigateToProfile) return@LaunchedEffect

        viewModel.clearProfileNavigation()
        onNavigateToProfile()
    }

    Content(
        uiState = uiState,
        onBackClick = onBackClick,
        onCreateProfile = onNavigateToCreateProfile,
        onIdentityClick = viewModel::onIdentityClick,
    )
}

@Composable
private fun Content(
    uiState: PubkyChoiceUiState,
    onBackClick: () -> Unit,
    onCreateProfile: () -> Unit,
    onIdentityClick: (String) -> Unit,
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
                    .fillMaxWidth(BG_IMAGE_WIDTH_FRACTION)
                    .align(Alignment.Center)
                    .offset(x = maxWidth * TAG_OFFSET_X, y = maxHeight * TAG_OFFSET_Y)
                    .alpha(TAG_ALPHA)
            )

            Image(
                painter = painterResource(R.drawable.keyring),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth(BG_IMAGE_WIDTH_FRACTION)
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
            )

            Column(modifier = Modifier.padding(horizontal = 32.dp)) {
                VerticalSpacer(24.dp)

                Display(
                    text = stringResource(R.string.profile__choice_title)
                        .withAccent(accentColor = Colors.PubkyGreen),
                    color = Colors.White,
                )
                VerticalSpacer(8.dp)

                BodyM(
                    text = stringResource(
                        if (uiState.identities.isEmpty()) {
                            R.string.profile__choice_description
                        } else {
                            R.string.profile__choice_description_ring
                        }
                    ),
                    color = Colors.White64,
                )
                VerticalSpacer(24.dp)

                when {
                    uiState.isLoading || uiState.adoptingPubky != null ->
                        LoadingState(text = stringResource(R.string.profile__choice_loading_profile))

                    uiState.identities.isEmpty() -> OptionCard(
                        iconResId = R.drawable.ic_user_plus,
                        text = stringResource(R.string.profile__choice_create),
                        onClick = onCreateProfile,
                        caption = stringResource(R.string.profile__choice_create_caption),
                        modifier = Modifier.testTag("PubkyChoiceCreate")
                    )

                    else -> uiState.identities.forEachIndexed { index, identity ->
                        if (index > 0) VerticalSpacer(8.dp)
                        OptionCard(
                            iconResId = R.drawable.ic_lock_key,
                            text = identity.name,
                            onClick = { onIdentityClick(identity.pubky) },
                            caption = identity.caption,
                            trailing = {
                                PubkyContactAvatar(
                                    profile = PubkyProfile.forDisplay(
                                        publicKey = identity.pubky,
                                        name = identity.name,
                                        imageUrl = identity.imageUrl,
                                    ),
                                    size = 32.dp,
                                )
                            },
                            modifier = Modifier.testTag("PubkyChoiceIdentity")
                        )
                    }
                }
            }

            FillHeight()
        }
    }
}

@Composable
private fun OptionCard(
    iconResId: Int,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Colors.Gray6)
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
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
        HorizontalSpacer(16.dp)
        Column(modifier = Modifier.weight(1f)) {
            caption?.let { Caption13Up(text = it, color = Colors.White64) }
            BodyMSB(text = text, color = Colors.White)
        }
        if (trailing != null) {
            trailing()
        }
    }
}

@Composable
private fun LoadingState(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        GradientCircularProgressIndicator(modifier = Modifier.size(20.dp))
        HorizontalSpacer(12.dp)
        BodyM(text = text, color = Colors.White64)
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewIdentities() {
    AppThemeSurface {
        Content(
            uiState = PubkyChoiceUiState(
                isLoading = false,
                identities = persistentListOf(
                    RingIdentity(
                        pubky = "a967rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roimbr4",
                        caption = "A967...MBR4",
                        name = "Satoshi Nakamoto",
                        imageUrl = null,
                    ),
                ),
            ),
            onBackClick = {},
            onCreateProfile = {},
            onIdentityClick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewCreate() {
    AppThemeSurface {
        Content(
            uiState = PubkyChoiceUiState(isLoading = false),
            onBackClick = {},
            onCreateProfile = {},
            onIdentityClick = {},
        )
    }
}
