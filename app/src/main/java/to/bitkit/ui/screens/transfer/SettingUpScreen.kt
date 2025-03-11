package to.bitkit.ui.screens.transfer

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.Title
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.transfer.components.ProgressSteps
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.localizedRandom
import to.bitkit.ui.utils.withAccent
import to.bitkit.viewmodels.TransferViewModel

@Composable
fun SettingUpScreen(
    viewModel: TransferViewModel,
    onContinueClick: () -> Unit = {},
    onCloseClick: () -> Unit = {},
) {
    val lightningSetupStep by viewModel.lightningSetupStep.collectAsState()
    SettingUpScreen(
        lightningSetupStep = lightningSetupStep,
        onContinueClick = {
            viewModel.resetSpendingState()
            onContinueClick()
        },
        onCloseClick = onCloseClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingUpScreen(
    lightningSetupStep: Int,
    onContinueClick: () -> Unit = {},
    onCloseClick: () -> Unit = {},
) {
    val inProgress = lightningSetupStep < 3
    ScreenColumn {
        CenterAlignedTopAppBar(
            title = {
                Title(
                    text = if (inProgress) {
                        stringResource(R.string.lightning__transfer__nav_title)
                    } else {
                        stringResource(R.string.lightning__transfer_success__nav_title)
                    }
                )
            },
            actions = {
                IconButton(onClick = onCloseClick) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.common__close),
                    )
                }
            },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))
            if (inProgress) {
                Display(
                    text = stringResource(R.string.lightning__savings_progress__title).withAccent(accentColor = Colors.Purple),
                )
                Spacer(modifier = Modifier.height(8.dp))
                BodyM(
                    text = stringResource(R.string.lightning__setting_up_text)
                        .withAccent(accentStyle = SpanStyle(color = Colors.White, fontWeight = FontWeight.Bold)),
                    color = Colors.White64,
                )
            } else {
                Display(
                    text = stringResource(R.string.lightning__transfer_success__title_spending)
                        .withAccent(accentColor = Colors.Purple),
                )
                Spacer(modifier = Modifier.height(8.dp))
                BodyM(
                    text = stringResource(R.string.lightning__transfer_success__text_spending),
                    color = Colors.White64,
                )
            }
            Spacer(modifier = Modifier.height(28.dp))
            if (inProgress) {
                TransferAnimationView()
                Spacer(modifier = Modifier.height(16.dp))
                val steps = listOf(
                    stringResource(R.string.lightning__setting_up_step1),
                    stringResource(R.string.lightning__setting_up_step2),
                    stringResource(R.string.lightning__setting_up_step3),
                    stringResource(R.string.lightning__setting_up_step4),
                )
                ProgressSteps(
                    steps = steps,
                    activeStepIndex = lightningSetupStep,
                    modifier = Modifier
                        .padding(vertical = 16.dp)
                        .align(alignment = Alignment.CenterHorizontally)
                )
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 16.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.check),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(256.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            val randomOkText = localizedRandom(R.string.common__ok_random)
            PrimaryButton(
                text = if (inProgress) {
                    stringResource(R.string.lightning__setting_up_button)
                } else {
                    randomOkText
                },
                onClick = onContinueClick,
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}


@Composable
private fun TransferAnimationView() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.padding(horizontal = 16.dp)
    ) {
        val infiniteTransition = rememberInfiniteTransition("transition")
        val rotationLarge by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = -180f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 3000, easing = EaseInOut),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "rotationLarge"
        )
        val rotationSmall by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 120f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 3000, easing = EaseInOut),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "rotationSmall"
        )
        val rotationArrows by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 70f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 3000, easing = EaseInOut),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "rotationArrows"
        )
        Image(
            painter = painterResource(id = R.drawable.ln_sync_large),
            contentDescription = null,
            modifier = Modifier
                .rotate(rotationLarge)
        )
        Image(
            painter = painterResource(id = R.drawable.ln_sync_small),
            contentDescription = null,
            modifier = Modifier
                .rotate(rotationSmall)
        )
        Image(
            painter = painterResource(id = R.drawable.transfer),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .rotate(rotationArrows)
        )
    }
}

@Preview(name = "Progress", showSystemUi = true, showBackground = true)
@Composable
private fun SettingUpScreenProgressPreview() {
    AppThemeSurface {
        SettingUpScreen(
            lightningSetupStep = 2,
        )
    }
}

@Preview(name = "Success", showSystemUi = true, showBackground = true)
@Composable
private fun SettingUpScreenSuccessPreview() {
    AppThemeSurface {
        SettingUpScreen(
            lightningSetupStep = 3,
        )
    }
}
