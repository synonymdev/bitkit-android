package to.bitkit.ui.screens.wallets.activity

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.LightningActivity
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentState
import com.synonym.bitkitcore.PaymentType
import org.lightningdevkit.ldknode.Network
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.ext.ellipsisMiddle
import to.bitkit.ext.rawId
import to.bitkit.ext.totalValue
import to.bitkit.models.Toast
import to.bitkit.ui.Routes
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BalanceHeaderView
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.wallets.activity.components.ActivityIcon
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.copyToClipboard
import to.bitkit.ui.utils.getScreenTitleRes
import to.bitkit.ui.utils.localizedPlural
import to.bitkit.utils.TxDetails
import to.bitkit.viewmodels.ActivityDetailViewModel
import to.bitkit.viewmodels.ActivityListViewModel

@Composable
fun ActivityExploreScreen(
    listViewModel: ActivityListViewModel,
    detailViewModel: ActivityDetailViewModel = hiltViewModel(),
    route: Routes.ActivityExplore,
    onBackClick: () -> Unit,
    onCloseClick: () -> Unit,
) {
    val activities by listViewModel.filteredActivities.collectAsStateWithLifecycle()
    val item = activities?.find { it.rawId() == route.id }
        ?: return

    val app = appViewModel ?: return
    val txDetails by detailViewModel.txDetails.collectAsStateWithLifecycle()
    val copyToastTitle = stringResource(R.string.common__copied)

    LaunchedEffect(item) {
        if (item is Activity.Onchain) {
            detailViewModel.fetchTransactionDetails(item.v1.txId)
        } else {
            detailViewModel.clearTransactionDetails()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            detailViewModel.clearTransactionDetails()
        }
    }

    ScreenColumn {
        AppTopBar(
            titleText = stringResource(item.getScreenTitleRes()),
            onBackClick = onBackClick,
            actions = { CloseNavIcon(onClick = onCloseClick) },
        )
        ActivityExploreContent(
            item = item,
            txDetails = txDetails,
            onCopy = { text ->
                app.toast(
                    type = Toast.ToastType.SUCCESS,
                    title = copyToastTitle,
                    description = text.ellipsisMiddle(40)
                )
            }
        )
    }
}

@Composable
private fun ActivityExploreContent(
    item: Activity,
    txDetails: TxDetails?,
    onCopy: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            val isSent = when (item) {
                is Activity.Lightning -> item.v1.txType == PaymentType.SENT
                is Activity.Onchain -> item.v1.txType == PaymentType.SENT
            }
            val amountPrefix = if (isSent) "-" else "+"
            BalanceHeaderView(
                sats = item.totalValue().toLong(),
                prefix = amountPrefix,
                showBitcoinSymbol = false,
                modifier = Modifier.weight(1f),
            )
            ActivityIcon(activity = item, size = 48.dp)
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (item) {
            is Activity.Onchain -> {
                OnchainDetails(onchain = item, onCopy = onCopy, txDetails = txDetails)
                Spacer(modifier = Modifier.weight(1f))
                PrimaryButton(
                    text = stringResource(R.string.wallet__activity_explorer),
                    onClick = handleExploreClick(item),
                )
            }

            is Activity.Lightning -> {
                LightningDetails(lightning = item, onCopy = onCopy)
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LightningDetails(
    lightning: Activity.Lightning,
    onCopy: (String) -> Unit,
) {
    val paymentHash = lightning.v1.id
    val preimage = lightning.v1.preimage
    val invoice = lightning.v1.invoice

    if (!preimage.isNullOrEmpty()) {
        Section(
            title = stringResource(R.string.wallet__activity_preimage),
            value = preimage,
            modifier = Modifier.clickableAlpha(onClick = copyToClipboard(preimage) {
                onCopy(preimage)
            }),
        )
    }
    Section(
        title = stringResource(R.string.wallet__activity_payment_hash),
        value = paymentHash,
        modifier = Modifier.clickableAlpha(onClick = copyToClipboard(paymentHash) {
            onCopy(paymentHash)
        }),
    )
    Section(
        title = stringResource(R.string.wallet__activity_invoice),
        value = invoice,
        modifier = Modifier.clickableAlpha(onClick = copyToClipboard(invoice) {
            onCopy(invoice)
        }),
    )
}

@Composable
private fun ColumnScope.OnchainDetails(
    onchain: Activity.Onchain,
    onCopy: (String) -> Unit,
    txDetails: TxDetails?,
) {
    val txId = onchain.v1.txId
    Section(
        title = stringResource(R.string.wallet__activity_tx_id),
        value = txId,
        modifier = Modifier.clickableAlpha(onClick = copyToClipboard(txId) {
            onCopy(txId)
        }),
    )
    if (txDetails != null) {
        Section(
            title = localizedPlural(R.string.wallet__activity_input, mapOf("count" to txDetails.vin.size)),
            valueContent = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    txDetails.vin.forEach { input ->
                        val text = "${input.txid}:${input.vout}"
                        BodySSB(text = text)
                    }
                }
            },
        )
        Section(
            title = localizedPlural(R.string.wallet__activity_output, mapOf("count" to txDetails.vout.size)),
            valueContent = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    txDetails.vout.forEach { output ->
                        val address = output.scriptpubkey_address.orEmpty()
                        BodySSB(text = address, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    }
                }
            },
        )
    } else {
        CircularProgressIndicator(
            strokeWidth = 2.dp,
            modifier = Modifier
                .size(16.dp)
                .padding(vertical = 16.dp)
                .align(Alignment.CenterHorizontally),
        )
    }
    // TODO add boosted parents info if boosted
}

@Composable
private fun Section(
    title: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    valueContent: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier) {
        Caption13Up(
            text = title,
            color = Colors.White64,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        )
        if (valueContent != null) {
            valueContent()
        } else if (value != null) {
            BodySSB(text = value)
        }
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
    }
}

@Composable
private fun handleExploreClick(
    onchain: Activity.Onchain,
): () -> Unit {
    val context = LocalContext.current
    val baseUrl = when (Env.network) {
        Network.TESTNET -> "https://mempool.space/testnet"
        else -> "https://mempool.space"
    }
    val url = "$baseUrl/tx/${onchain.v1.txId}"
    val intent = Intent(Intent.ACTION_VIEW, url.toUri())

    return { context.startActivity(intent) }
}

@Preview
@Composable
private fun PreviewLightning() {
    AppThemeSurface {
        ActivityExploreContent(
            item = Activity.Lightning(
                v1 = LightningActivity(
                    id = "test-lightning-1",
                    txType = PaymentType.SENT,
                    status = PaymentState.SUCCEEDED,
                    value = 50000UL,
                    fee = 1UL,
                    invoice = "lnbc...",
                    message = "Thanks for paying at the bar. Here's my share.",
                    timestamp = (System.currentTimeMillis() / 1000).toULong(),
                    preimage = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                    createdAt = null,
                    updatedAt = null,
                ),
            ),
            txDetails = null,
            onCopy = {},
        )
    }
}

@Preview
@Composable
private fun PreviewOnchain() {
    AppThemeSurface {
        ActivityExploreContent(
            item = Activity.Onchain(
                v1 = OnchainActivity(
                    id = "test-onchain-1",
                    txType = PaymentType.RECEIVED,
                    txId = "abc123",
                    value = 100000UL,
                    fee = 500UL,
                    feeRate = 8UL,
                    address = "bc1...",
                    confirmed = true,
                    timestamp = (System.currentTimeMillis() / 1000 - 3600).toULong(),
                    isBoosted = false,
                    isTransfer = false,
                    doesExist = true,
                    confirmTimestamp = (System.currentTimeMillis() / 1000).toULong(),
                    channelId = null,
                    transferTxId = null,
                    createdAt = null,
                    updatedAt = null,
                ),
            ),
            txDetails = null,
            onCopy = {},
        )
    }
}
