package to.bitkit.ui.screens.wallet.sheets

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.ui.AppViewModel
import to.bitkit.ui.shared.moneyString
import to.bitkit.ui.theme.AppShapes
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewTransactionSheet(
    appViewModel: AppViewModel = hiltViewModel(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = { appViewModel.showNewTransaction = false },
        sheetState = sheetState,
        shape = AppShapes.sheet,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxHeight()
            .padding(top = 100.dp)
    ) {
        NewTransactionSheetView(appViewModel.newTransaction, sheetState, appViewModel)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewTransactionSheetView(
    details: NewTransactionSheetDetails,
    sheetState: SheetState,
    appViewModel: AppViewModel? = null,
) {
    val rotateAnim = remember { Animatable(0f) }
    val offsetYAnim = remember { Animatable(0f) }

    LaunchedEffect(sheetState.isVisible) {
        if (sheetState.isVisible) {
            launch {
                rotateAnim.animateTo(
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(3_200, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart,
                    )
                )
            }
            launch {
                offsetYAnim.animateTo(
                    targetValue = 100f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(10_000, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                    )
                )
            }
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Text(
            text = when (details.type) {
                NewTransactionSheetType.LIGHTNING -> when (details.direction) {
                    NewTransactionSheetDirection.SENT -> "Sent Instant Bitcoin"
                    else -> "Received Instant Bitcoin"
                }

                NewTransactionSheetType.ONCHAIN -> when (details.direction) {
                    NewTransactionSheetDirection.SENT -> "Sent Bitcoin"
                    else -> "Received Bitcoin"
                }
            },
            style = MaterialTheme.typography.titleMedium,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = moneyString(details.sats),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.align(Alignment.Start)
        )

        Spacer(modifier = Modifier.weight(1f))

        // Confetti animation (rotation and vertical movement)
        Text(
            text = "🎉",
            modifier = Modifier
                .rotate(rotateAnim.value)
                .offset { IntOffset(x = 0, y = offsetYAnim.value.roundToInt()) }
        )

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = { appViewModel?.showNewTransaction = false }
        ) {
            Text("Close")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true)
@Composable
private fun PreviewNewTransactionSheetView() {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(Unit) {
        sheetState.show()
    }

    NewTransactionSheetView(
        details = NewTransactionSheetDetails(
            type = NewTransactionSheetType.LIGHTNING,
            direction = NewTransactionSheetDirection.RECEIVED,
            sats = 123456789,
        ),
        sheetState,
    )
}
