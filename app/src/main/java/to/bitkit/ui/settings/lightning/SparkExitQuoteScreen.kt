package to.bitkit.ui.settings.lightning

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

/** What the exit takes on-chain, what it costs, and what stays behind. */
@Composable
fun SparkExitQuoteScreen(
    viewModel: SparkExitViewModel,
    onBack: () -> Unit,
    onContinue: (needsFunding: Boolean) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadQuote() }
    Content(uiState = uiState, onBack = onBack, onContinue = { onContinue(uiState.fundingSats > 0uL) })
}

@Composable
private fun Content(
    uiState: SparkExitUiState,
    onBack: () -> Unit = {},
    onContinue: () -> Unit = {},
) {
    val block = uiState.quoteBlock
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__spark_exit__nav_title),
            onBackClick = onBack,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            VerticalSpacer(32.dp)
            Display(text = stringResource(R.string.lightning__spark_exit__quote_title).withAccent())
            VerticalSpacer(32.dp)
            when {
                uiState.quote != null -> {
                    SparkExitQuoteRows(uiState.quote)
                    uiState.quote.unexitableSats?.let { UnexitableNote(it) }
                }
                uiState.isQuoting && uiState.isSettling -> BodyM(
                    text = stringResource(R.string.lightning__spark_exit__quote_settling),
                )
                uiState.isQuoting -> BodyM(text = stringResource(R.string.lightning__spark_exit__quote_loading))
            }
            block?.let {
                VerticalSpacer(16.dp)
                BodyM(
                    text = stringResource(
                        when (it) {
                            SparkExitQuoteBlock.Error -> R.string.lightning__spark_exit__quote_error
                            SparkExitQuoteBlock.Nothing -> R.string.lightning__spark_exit__nothing
                            SparkExitQuoteBlock.Uneconomic -> R.string.lightning__spark_exit__uneconomic
                        },
                    ),
                    color = Colors.Red,
                    modifier = Modifier.testTag("SparkExitQuoteBlocked")
                )
            }
            VerticalSpacer(32.dp)
        }
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            PrimaryButton(
                text = stringResource(R.string.common__continue),
                onClick = onContinue,
                isLoading = uiState.isQuoting,
                enabled = uiState.quote != null && block == null,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("SparkExitQuoteContinue")
            )
            VerticalSpacer(16.dp)
        }
    }
}

/** What the quote cannot exit: skipped leaves, and value still moving inside Spark. */
@Composable
private fun UnexitableNote(sats: ULong) {
    VerticalSpacer(16.dp)
    BodyS(
        text = stringResource(R.string.lightning__spark_exit__quote_outside)
            .replace("{amount}", sparkExitAmountText(sats)),
        color = Colors.White64,
        modifier = Modifier.testTag("SparkExitQuoteOutside")
    )
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(uiState = SparkExitUiState(isQuoting = true))
    }
}
