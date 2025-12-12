package to.bitkit.ui.screens.wallets.activity

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
import org.lightningdevkit.ldknode.TransactionDetails
import to.bitkit.R
import to.bitkit.ext.ellipsisMiddle
import to.bitkit.ext.isSent
import to.bitkit.ext.totalValue
import to.bitkit.models.Toast
import to.bitkit.ui.Routes
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BalanceHeaderView
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.wallets.activity.components.ActivityIcon
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.copyToClipboard
import to.bitkit.ui.utils.getBlockExplorerUrl
import to.bitkit.ui.utils.getScreenTitleRes
import to.bitkit.ui.utils.localizedPlural
import to.bitkit.viewmodels.ActivityDetailViewModel

@Composable
fun ActivityExploreScreen(
    detailViewModel: ActivityDetailViewModel = hiltViewModel(),
    route: Routes.ActivityExplore,
    onBackClick: () -> Unit,
) {
    val uiState by detailViewModel.uiState.collectAsStateWithLifecycle()

    // Load activity on composition
    LaunchedEffect(route.id) {
        detailViewModel.loadActivity(route.id)
    }

    // Clear state on disposal
    DisposableEffect(Unit) {
        onDispose {
            detailViewModel.clearActivityState()
        }
    }

    ScreenColumn {
        when (val loadState = uiState.activityLoadState) {
            is ActivityDetailViewModel.ActivityLoadState.Initial,
            is ActivityDetailViewModel.ActivityLoadState.Loading,
            -> {
                AppTopBar(
                    titleText = stringResource(R.string.wallet__activity),
                    onBackClick = onBackClick,
                    actions = { DrawerNavIcon() },
                )
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            is ActivityDetailViewModel.ActivityLoadState.Error -> {
                AppTopBar(
                    titleText = stringResource(R.string.wallet__activity),
                    onBackClick = onBackClick,
                    actions = { DrawerNavIcon() },
                )
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    BodySSB(
                        text = loadState.message,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    PrimaryButton(
                        text = stringResource(R.string.common__back),
                        onClick = onBackClick
                    )
                }
            }

            is ActivityDetailViewModel.ActivityLoadState.Success -> {
                val item = loadState.activity
                val app = appViewModel ?: return@ScreenColumn
                val context = LocalContext.current

                val txDetails by detailViewModel.txDetails.collectAsStateWithLifecycle()
                var boostTxDoesExist by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

                LaunchedEffect(item) {
                    if (item is Activity.Onchain) {
                        detailViewModel.fetchTransactionDetails(item.v1.txId)
                        if (item.v1.boostTxIds.isNotEmpty()) {
                            boostTxDoesExist = detailViewModel.getBoostTxDoesExist(item.v1.boostTxIds)
                        }
                    } else {
                        detailViewModel.clearTransactionDetails()
                    }
                }

                DisposableEffect(Unit) {
                    onDispose {
                        detailViewModel.clearTransactionDetails()
                    }
                }

                AppTopBar(
                    titleText = stringResource(item.getScreenTitleRes()),
                    onBackClick = onBackClick,
                    actions = { DrawerNavIcon() },
                )

                val toastMessage = stringResource(R.string.common__copied)
                ActivityExploreContent(
                    item = item,
                    txDetails = txDetails,
                    boostTxDoesExist = boostTxDoesExist,
                    onCopy = { text ->
                        app.toast(
                            type = Toast.ToastType.SUCCESS,
                            title = toastMessage,
                            description = text.ellipsisMiddle(40),
                        )
                    },
                    onClickExplore = { txid ->
                        val url = getBlockExplorerUrl(txid)
                        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                        context.startActivity(intent)
                    },
                )
            }
        }
    }
}

@Composable
private fun ActivityExploreContent(
    item: Activity,
    txDetails: TransactionDetails? = null,
    boostTxDoesExist: Map<String, Boolean> = emptyMap(),
    onCopy: (String) -> Unit = {},
    onClickExplore: (String) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            BalanceHeaderView(
                sats = item.totalValue().toLong(),
                prefix = if (item.isSent()) "-" else "+",
                showBitcoinSymbol = false,
                modifier = Modifier.weight(1f),
            )
            ActivityIcon(activity = item, size = 48.dp)
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (item) {
            is Activity.Onchain -> {
                OnchainDetails(
                    onchain = item,
                    onCopy = onCopy,
                    txDetails = txDetails,
                    boostTxDoesExist = boostTxDoesExist,
                )
                Spacer(modifier = Modifier.weight(1f))
                PrimaryButton(
                    text = stringResource(R.string.wallet__activity_explorer),
                    onClick = { onClickExplore(item.v1.txId) },
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
            modifier = Modifier.clickableAlpha(
                onClick = copyToClipboard(preimage) {
                    onCopy(it)
                }
            ),
        )
    }
    Section(
        title = stringResource(R.string.wallet__activity_payment_hash),
        value = paymentHash,
        modifier = Modifier.clickableAlpha(
            onClick = copyToClipboard(paymentHash) {
                onCopy(it)
            }
        ),
    )
    Section(
        title = stringResource(R.string.wallet__activity_invoice),
        value = invoice,
        modifier = Modifier.clickableAlpha(
            onClick = copyToClipboard(invoice) {
                onCopy(it)
            }
        ),
    )
}

@Composable
private fun ColumnScope.OnchainDetails(
    onchain: Activity.Onchain,
    onCopy: (String) -> Unit,
    txDetails: TransactionDetails?,
    boostTxDoesExist: Map<String, Boolean> = emptyMap(),
) {
    val txId = onchain.v1.txId
    Section(
        title = stringResource(R.string.wallet__activity_tx_id),
        value = txId,
        modifier = Modifier
            .clickableAlpha(
                onClick = copyToClipboard(txId) {
                    onCopy(it)
                }
            )
            .testTag("TXID")
    )
    if (txDetails != null) {
        Section(
            title = localizedPlural(R.string.wallet__activity_input, mapOf("count" to txDetails.inputs.size)),
            valueContent = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    txDetails.inputs.forEach { input ->
                        val text = "${input.txid}:${input.vout}"
                        BodySSB(text = text, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    }
                }
            },
        )
        Section(
            title = localizedPlural(R.string.wallet__activity_output, mapOf("count" to txDetails.outputs.size)),
            valueContent = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    txDetails.outputs.forEach { output ->
                        val address = output.scriptpubkeyAddress ?: ""
                        BodySSB(text = address, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    }
                }
            },
        )
    } else {
        CircularProgressIndicator(
            strokeWidth = 2.dp,
            modifier = Modifier
                .padding(vertical = 16.dp)
                .size(16.dp)
                .align(Alignment.CenterHorizontally)
        )
    }

    // Display boosted transaction IDs from boostTxIds
    // For CPFP (RECEIVED): shows child transaction IDs that boosted this parent
    // For RBF (SENT): shows parent transaction IDs that this replacement replaced
    val boostTxIds = onchain.v1.boostTxIds
    if (boostTxIds.isNotEmpty()) {
        boostTxIds.forEachIndexed { index, boostedTxId ->
            val boostTxDoesExistValue = boostTxDoesExist[boostedTxId] ?: true
            val isRbf = !boostTxDoesExistValue
            Section(
                title = stringResource(
                    if (isRbf) R.string.wallet__activity_boosted_rbf else R.string.wallet__activity_boosted_cpfp
                ).replace("{num}", "${index + 1}"),
                valueContent = {
                    Column {
                        BodySSB(text = boostedTxId, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    }
                },
                modifier = Modifier
                    .clickableAlpha(
                        onClick = copyToClipboard(boostedTxId) {
                            onCopy(it)
                        }
                    )
                    .testTag(if (isRbf) "RBFBoosted" else "CPFPBoosted")
            )
        }
    }
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
                    boostTxIds = emptyList(),
                    isTransfer = false,
                    doesExist = true,
                    confirmTimestamp = (System.currentTimeMillis() / 1000).toULong(),
                    channelId = null,
                    transferTxId = null,
                    createdAt = null,
                    updatedAt = null,
                ),
            ),
        )
    }
}
