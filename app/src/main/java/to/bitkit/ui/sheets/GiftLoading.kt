package to.bitkit.ui.sheets

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.models.BITCOIN_SYMBOL
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.formatToModernDisplay
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.MoneySSB
import to.bitkit.ui.components.SheetSize
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.Colors

@Composable
fun GiftLoading(amount: ULong) {
    Content(amount = amount)
}

@Composable
private fun Content(
    amount: ULong,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .sheetHeight(SheetSize.LARGE)
            .gradientBackground()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        SheetTopBar(titleText = stringResource(R.string.other__gift__claiming__title))
        VerticalSpacer(16.dp)

        val currencies = LocalCurrencies.current
        val currency = currencyViewModel
        val primaryDisplay = currencies.primaryDisplay

        if (primaryDisplay == PrimaryDisplay.BITCOIN) {
            MoneySSB(
                sats = amount.toLong(),
                unit = PrimaryDisplay.FIAT,
                color = Colors.White64,
                showSymbol = true,
                modifier = Modifier.align(Alignment.Start),
            )
            VerticalSpacer(16.dp)
            val bitcoinAmount = remember(amount, currency, currencies) {
                currency?.convert(amount.toLong())?.bitcoinDisplay(currencies.displayUnit)?.value
                    ?: amount.formatToModernDisplay()
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.Start),
            ) {
                Display(
                    text = BITCOIN_SYMBOL,
                    color = Colors.White,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Display(
                    text = bitcoinAmount,
                    color = Colors.White,
                )
            }
        } else {
            MoneySSB(
                sats = amount.toLong(),
                unit = PrimaryDisplay.BITCOIN,
                color = Colors.White64,
                showSymbol = true,
                modifier = Modifier.align(Alignment.Start),
            )
            VerticalSpacer(16.dp)
            val fiatAmount = remember(amount, currency) {
                currency?.convert(amount.toLong())?.formatted ?: ""
            }
            val fiatSymbol = remember(amount, currency) {
                currency?.convert(amount.toLong())?.symbol ?: ""
            }
            if (fiatAmount.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.Start),
                ) {
                    Display(
                        text = fiatSymbol,
                        color = Colors.White,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    Display(
                        text = fiatAmount,
                        color = Colors.White,
                    )
                }
            }
        }
        VerticalSpacer(32.dp)

        BodyM(
            text = stringResource(R.string.other__gift__claiming__text),
            color = Colors.White64,
        )

        FillHeight()

        Image(
            painter = painterResource(R.drawable.gift),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth(IMAGE_WIDTH_FRACTION)
                .aspectRatio(1.0f)
                .align(Alignment.CenterHorizontally)
        )

        VerticalSpacer(32.dp)

        CircularProgressIndicator(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 32.dp)
                .testTag("GiftLoading")
        )
    }
}
