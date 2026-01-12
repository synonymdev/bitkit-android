package to.bitkit.ui.settings.advanced.sweep

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.rememberLottieComposition
import to.bitkit.R
import to.bitkit.ui.Routes
import to.bitkit.ui.components.BalanceHeaderView
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun SweepSuccessScreen(
    navController: NavController,
    amountSats: Long,
) {
    Content(
        amountSats = amountSats,
        onDone = {
            navController.navigate(Routes.Home) {
                popUpTo(Routes.Home) { inclusive = true }
            }
        },
    )
}

@Composable
private fun Content(
    amountSats: Long = 0L,
    onDone: () -> Unit = {},
) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.confetti_orange))

    Box(modifier = Modifier.fillMaxSize()) {
        LottieAnimation(
            composition = composition,
            iterations = 100,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .testTag("confetti_animation")
        )

        ScreenColumn(noBackground = true) {
            AppTopBar(
                titleText = stringResource(R.string.sweep__success_nav_title),
                onBackClick = null,
                actions = { DrawerNavIcon() },
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .testTag("SweepSuccess")
            ) {
                VerticalSpacer(16.dp)

                BodyM(
                    text = stringResource(R.string.sweep__success_description),
                    color = Colors.White64,
                )

                VerticalSpacer(16.dp)

                BalanceHeaderView(sats = amountSats)

                Spacer(modifier = Modifier.weight(1f))

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Image(
                        painter = painterResource(R.drawable.check),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(256.dp)
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                PrimaryButton(
                    text = stringResource(R.string.sweep__success_wallet_overview),
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth()
                )

                VerticalSpacer(16.dp)
            }
        }
    }
}

@Preview(showSystemUi = true, showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(amountSats = 18000L)
    }
}
