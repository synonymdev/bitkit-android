package to.bitkit.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ui.nav.Navigator
import to.bitkit.ui.nav.Routes
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.shared.util.blockPointerInputPassthrough
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.theme.InterFontFamily

private const val zIndexScrim = 10f
private const val zIndexMenu = 11f
private val bgScrim = Colors.Black50
private val drawerBg = Colors.Brand
private val drawerWidth = 200.dp

@Composable
fun DrawerMenu(
    drawerState: DrawerState,
    navigator: Navigator,
    hasSeenWidgetsIntro: Boolean,
    hasSeenShopIntro: Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    Box(modifier = modifier.fillMaxSize()) {
        Scrim(
            visible = drawerState.currentValue == DrawerValue.Open,
            onClick = {
                scope.launch {
                    drawerState.close()
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .zIndex(zIndexScrim)
        )

        AnimatedVisibility(
            visible = drawerState.currentValue == DrawerValue.Open,
            enter = slideInHorizontally(
                initialOffsetX = { it }
            ),
            exit = slideOutHorizontally(
                targetOffsetX = { it }
            ),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxHeight()
                .zIndex(zIndexMenu)
                .blockPointerInputPassthrough()
        ) {
            MenuContent(
                onWalletClick = {
                    if (!navigator.isHome()) navigator.navigateHome()
                    scope.launch { drawerState.close() }
                },
                onActivityClick = {
                    navigator.navigate(Routes.Activities.All)
                    scope.launch { drawerState.close() }
                },
                onContactsClick = null, // TODO IMPLEMENT CONTACTS
                onProfileClick = null, // TODO IMPLEMENT PROFILE
                onWidgetsClick = {
                    if (!hasSeenWidgetsIntro) {
                        navigator.navigate(Routes.Widgets.Intro)
                    } else {
                        navigator.navigate(Routes.Widgets.Add)
                    }
                    scope.launch { drawerState.close() }
                },
                onShopClick = {
                    if (!hasSeenShopIntro) {
                        navigator.navigate(Routes.Shop.Intro)
                    } else {
                        navigator.navigate(Routes.Shop.Discover)
                    }
                    scope.launch { drawerState.close() }
                },
                onSettingsClick = {
                    navigator.navigate(Routes.Settings.Main)
                    scope.launch { drawerState.close() }
                },
                onAppStatusClick = {
                    navigator.navigate(Routes.AppStatus)
                    scope.launch { drawerState.close() }
                },
            )
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun MenuContent(
    onWalletClick: () -> Unit,
    onActivityClick: () -> Unit,
    onContactsClick: (() -> Unit)?,
    onProfileClick: (() -> Unit)?,
    onWidgetsClick: () -> Unit,
    onShopClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onAppStatusClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(drawerWidth)
            .fillMaxHeight()
            .background(drawerBg)
            .systemBarsPadding()
    ) {
        VerticalSpacer(16.dp)

        DrawerItem(
            label = stringResource(R.string.wallet__drawer__wallet),
            iconRes = R.drawable.ic_coins,
            onClick = onWalletClick,
            modifier = Modifier.testTag("DrawerWallet")
        )

        DrawerItem(
            label = stringResource(R.string.wallet__drawer__activity),
            iconRes = R.drawable.ic_heartbeat,
            onClick = onActivityClick,
            modifier = Modifier.testTag("DrawerActivity")
        )

        DrawerItem(
            label = stringResource(R.string.wallet__drawer__contacts),
            iconRes = R.drawable.ic_users,
            onClick = onContactsClick,
            modifier = Modifier.testTag("DrawerContacts")
        )

        DrawerItem(
            label = stringResource(R.string.wallet__drawer__profile),
            iconRes = R.drawable.ic_user_square,
            onClick = onProfileClick,
            modifier = Modifier.testTag("DrawerProfile")
        )

        DrawerItem(
            label = stringResource(R.string.wallet__drawer__widgets),
            iconRes = R.drawable.ic_stack,
            onClick = onWidgetsClick,
            modifier = Modifier.testTag("DrawerWidgets")
        )

        DrawerItem(
            label = stringResource(R.string.wallet__drawer__shop),
            iconRes = R.drawable.ic_store_front,
            onClick = onShopClick,
            modifier = Modifier.testTag("DrawerShop")
        )

        DrawerItem(
            label = stringResource(R.string.wallet__drawer__settings),
            iconRes = R.drawable.ic_settings,
            onClick = onSettingsClick,
            modifier = Modifier.testTag("DrawerSettings")
        )

        FillHeight()

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickableAlpha(onClick = onAppStatusClick)
        ) {
            AppStatus(
                showText = true,
                showReady = true,
                color = Colors.Black,
                modifier = Modifier
                    .padding(vertical = 16.dp)
                    .testTag("DrawerAppStatus")
            )
        }
    }
}

@Composable
private fun Scrim(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bgScrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                )
        )
    }
}

@Composable
private fun DrawerItem(
    label: String,
    @DrawableRes iconRes: Int,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .then(
                if (onClick != null) {
                    Modifier.clickable { onClick() }
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 16.dp)
    ) {
        VerticalSpacer(16.dp)
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )

            Text(
                text = label.uppercase(),
                style = TextStyle(
                    fontWeight = FontWeight.Black,
                    fontSize = 24.sp,
                    lineHeight = 24.sp,
                    letterSpacing = (-1).sp,
                    fontFamily = InterFontFamily,
                    color = Colors.White,
                ),
                modifier = Modifier.weight(1f)
            )
        }
        VerticalSpacer(16.dp)
        HorizontalDivider()
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.align(Alignment.TopEnd)) {
                MenuContent(
                    onWalletClick = {},
                    onActivityClick = {},
                    onContactsClick = null,
                    onProfileClick = null,
                    onWidgetsClick = {},
                    onShopClick = {},
                    onSettingsClick = {},
                    onAppStatusClick = {},
                )
            }
        }
    }
}
