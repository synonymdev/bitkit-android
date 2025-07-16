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
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ui.Routes
import to.bitkit.ui.navigateToSettings
import to.bitkit.ui.screens.wallets.HomeRoutes
import to.bitkit.ui.shared.util.blockPointerInputPassthrough
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.theme.InterFontFamily

@Composable
fun DrawerMenu(
    drawerState: DrawerState,
    walletNavController: NavController,
    rootNavController: NavController,
    hasSeenWidgetsIntro: Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    // overlay background
    AnimatedVisibility(
        visible = drawerState.currentValue == DrawerValue.Open,
        modifier = Modifier
            .fillMaxSize()
            .zIndex(10f) // Higher z-index than TabBar
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Colors.Black50)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    scope.launch {
                        drawerState.close()
                    }
                }
        )
    }

    // Right-side drawer content
    AnimatedVisibility(
        visible = drawerState.currentValue == DrawerValue.Open,
        enter = slideInHorizontally(
            initialOffsetX = { it }
        ),
        exit = slideOutHorizontally(
            targetOffsetX = { it }
        ),
        modifier = modifier.then(
            Modifier
                .fillMaxHeight()
                .zIndex(11f) // Higher z-index than overlay
                .blockPointerInputPassthrough()
        )
    ) {
        DrawerContent(
            walletNavController = walletNavController,
            rootNavController = rootNavController,
            drawerState = drawerState,
            onClickAddWidget = {
                if (!hasSeenWidgetsIntro) {
                    rootNavController.navigate(Routes.WidgetsIntro)
                } else {
                    rootNavController.navigate(Routes.AddWidget)
                }
            }
        )
    }
}

@Composable
fun DrawerContent(
    walletNavController: NavController,
    rootNavController: NavController,
    drawerState: DrawerState,
    onClickAddWidget: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val drawerWidth = 200.dp

    Column(
        modifier = Modifier
            .width(drawerWidth)
            .fillMaxHeight()
            .background(Colors.Brand)
            .padding(horizontal = 16.dp)
            .systemBarsPadding()
    ) {
        VerticalSpacer(60.dp)

        DrawerItem(
            label = stringResource(R.string.wallet__drawer__wallet),
            iconRes = R.drawable.ic_coins,
            onClick = {
                scope.launch { drawerState.close() }
            },
        )

        DrawerItem(
            label = stringResource(R.string.wallet__drawer__activity),
            iconRes = R.drawable.ic_heartbeat,
            onClick = {
                walletNavController.navigate(HomeRoutes.AllActivity)
                scope.launch { drawerState.close() }
            },
        )

        DrawerItem(
            label = stringResource(R.string.wallet__drawer__contacts),
            iconRes = R.drawable.ic_users,
            onClick = null, // TODO IMPLEMENT CONTACTS
        )

        DrawerItem(
            label = stringResource(R.string.wallet__drawer__profile),
            iconRes = R.drawable.ic_user_square,
            onClick = null, // TODO IMPLEMENT PROFILE
        )

        DrawerItem(
            label = stringResource(R.string.wallet__drawer__widgets),
            iconRes = R.drawable.ic_stack,
            onClick = {
                onClickAddWidget()
                scope.launch { drawerState.close() }
            }
        )

        DrawerItem(
            label = stringResource(R.string.wallet__drawer__shop),
            iconRes = R.drawable.ic_store_front,
            onClick = {
                rootNavController.navigate(Routes.ShopDiscover)
                scope.launch { drawerState.close() }
            }
        )

        DrawerItem(
            label = stringResource(R.string.wallet__drawer__settings),
            iconRes = R.drawable.ic_settings,
            onClick = {
                rootNavController.navigateToSettings()
                scope.launch { drawerState.close() }
            },
        )

        // TODO add app state menu component & nav to screen
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
        modifier = modifier.then(
            if (onClick != null) {
                Modifier.clickable { onClick() }
            } else {
                Modifier
            }
        )
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
        val navController = rememberNavController()
        Box {
            DrawerMenu(
                walletNavController = navController,
                rootNavController = navController,
                drawerState = rememberDrawerState(initialValue = DrawerValue.Open),
                hasSeenWidgetsIntro = false,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}
