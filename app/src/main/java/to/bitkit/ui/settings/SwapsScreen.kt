package to.bitkit.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synonym.bitkitcore.BoltzSwap
import com.synonym.bitkitcore.PubkyDeliveryOperation
import com.synonym.bitkitcore.PubkyDeliveryStatus
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import to.bitkit.ext.formatToString
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.models.PubkySwapProvider
import to.bitkit.models.Toast
import to.bitkit.models.formatToModernDisplay
import to.bitkit.repositories.PubkySwapState
import to.bitkit.ui.Routes
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.CaptionB
import to.bitkit.ui.components.Footnote
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.settings.DetailRow
import to.bitkit.ui.components.settings.InfoCard
import to.bitkit.ui.components.settings.InfoCell
import to.bitkit.ui.components.settings.cardColors
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.AppShapes
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.copyToClipboard
import to.bitkit.viewmodels.SwapsViewModel
import to.bitkit.viewmodels.isClaimable

@Composable
fun SwapsScreen(
    onBackClick: () -> Unit,
    onSwapItemClick: (String) -> Unit,
    viewModel: SwapsViewModel = hiltViewModel(),
) {
    val app = appViewModel
    val swaps by viewModel.swaps.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val providerState by viewModel.providerState.collectAsStateWithLifecycle()
    val deliveries by viewModel.deliveries.collectAsStateWithLifecycle()
    fun onResult(action: String): (Result<Unit>) -> Unit = { result ->
        result
            .onSuccess { app?.toast(type = Toast.ToastType.SUCCESS, title = "$action done") }
            .onFailure {
                app?.toast(
                    type = Toast.ToastType.ERROR,
                    title = "$action failed",
                    description = it.message ?: "Unknown error",
                )
            }
    }

    SwapsContent(
        swaps = swaps,
        error = error,
        providerState = providerState,
        deliveries = deliveries,
        onBack = onBackClick,
        onClickSwap = onSwapItemClick,
        onReconnect = { viewModel.reconnectProvider(onResult("Reconnect")) },
        onDisconnect = { viewModel.disconnectProvider(onResult("Disconnect")) },
        onRetryDelivery = { id, operation -> viewModel.retryDelivery(id, operation, onResult("Retry")) },
    )
}

@Composable
private fun SwapsContent(
    swaps: ImmutableList<BoltzSwap>,
    error: String?,
    modifier: Modifier = Modifier,
    providerState: PubkySwapState = PubkySwapState.NoProvider,
    deliveries: ImmutableList<PubkyDeliveryStatus> = persistentListOf(),
    onBack: () -> Unit = {},
    onClickSwap: (String) -> Unit = {},
    onReconnect: () -> Unit = {},
    onDisconnect: () -> Unit = {},
    onRetryDelivery: (String, PubkyDeliveryOperation) -> Unit = { _, _ -> },
) {
    Scaffold(
        topBar = { AppTopBar(titleText = "Swaps", onBackClick = onBack) },
        modifier = modifier,
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(padding),
        ) {
            item { ProviderCard(providerState, onReconnect, onDisconnect) }
            if (deliveries.isNotEmpty()) {
                item { DeliveryCard(deliveries, onRetryDelivery) }
            }
            error?.let {
                item { BodyS(text = "Error: $it") }
            }
            if (swaps.isEmpty()) {
                item { BodyS(text = "No swaps found…") }
            } else {
                items(swaps) { swap -> SwapCard(swap, onClickSwap) }
            }
        }
    }
}

@Composable
private fun ProviderCard(
    state: PubkySwapState,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val provider = state.provider
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        InfoCard(header = "Pubky swap provider") {
            DetailRow("Status", state.statusText(), isError = state is PubkySwapState.Failed)
            DetailRow("Provider", provider?.publicKey?.let(PubkyPublicKeyFormat::display) ?: "-")
            DetailRow("Network", provider?.network?.wireValue ?: "-")
            DetailRow(
                "Identity",
                (state as? PubkySwapState.Connected)?.identity?.let(PubkyPublicKeyFormat::display) ?: "-",
            )
        }
        if (provider != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(
                    text = "Reconnect",
                    onClick = onReconnect,
                    size = ButtonSize.Small,
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(
                    text = "Disconnect",
                    onClick = onDisconnect,
                    size = ButtonSize.Small,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DeliveryCard(
    deliveries: ImmutableList<PubkyDeliveryStatus>,
    onRetry: (String, PubkyDeliveryOperation) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        InfoCard(header = "Pubky delivery") {
            deliveries.forEach { delivery ->
                val state = when {
                    delivery.publicationPaused -> "Publication paused"
                    delivery.cleanupPaused -> "Cleanup paused"
                    delivery.published -> "Published"
                    else -> "Pending"
                }
                DetailRow(delivery.id, state, isError = delivery.publicationPaused || delivery.cleanupPaused)
                (delivery.publicationFailure ?: delivery.cleanupFailure)?.let {
                    DetailRow("Failure", it, isError = true)
                }
            }
        }
        deliveries.filter { it.publicationPaused || it.cleanupPaused }.forEach { delivery ->
            val operation = when {
                delivery.publicationPaused -> PubkyDeliveryOperation.PUBLICATION
                else -> PubkyDeliveryOperation.CLEANUP
            }
            PrimaryButton(
                text = "Retry ${operation.name.lowercase()} of ${delivery.id.take(8)}",
                onClick = { onRetry(delivery.id, operation) },
                size = ButtonSize.Small,
            )
        }
    }
}

private val PubkySwapState.provider: PubkySwapProvider?
    get() = when (this) {
        PubkySwapState.NoProvider -> null
        is PubkySwapState.IdentityMissing -> provider
        is PubkySwapState.Connecting -> provider
        is PubkySwapState.Connected -> provider
        is PubkySwapState.SwitchBlocked -> provider
        is PubkySwapState.Failed -> provider
    }

private fun PubkySwapState.statusText(): String = when (this) {
    PubkySwapState.NoProvider -> "No provider"
    is PubkySwapState.IdentityMissing -> "Pubky identity missing"
    is PubkySwapState.Connecting -> "Connecting"
    is PubkySwapState.Connected -> "Connected"
    is PubkySwapState.SwitchBlocked -> "Kept for pending swaps"
    is PubkySwapState.Failed -> "Failed: ${message ?: "unknown"}"
}

@Composable
private fun SwapCard(model: BoltzSwap, onClick: (String) -> Unit) {
    Card(
        colors = cardColors,
        modifier = Modifier
            .fillMaxWidth()
            .clickableAlpha { onClick(model.id) }
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CaptionB(
                    text = model.id,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickableAlpha(onClick = copyToClipboard(model.id))
                )
                HorizontalSpacer(8.dp)
                Surface(color = Colors.White16, shape = AppShapes.small) {
                    Footnote(
                        text = model.status.toString(),
                        color = Colors.White64,
                        maxLines = 1,
                        modifier = Modifier.padding(4.dp)
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                InfoCell(label = "Type", value = model.swapType.toString())
                InfoCell(
                    label = "Amount",
                    value = "${model.amountSat.formatToModernDisplay()} sats",
                    alignment = Alignment.End,
                )
            }

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                InfoCell(
                    label = "Receives",
                    value = model.onchainAmountSat?.let { "${it.formatToModernDisplay()} sats" } ?: "-",
                )
                InfoCell(
                    label = "Created",
                    value = model.createdAt.formatToString().orEmpty(),
                    alignment = Alignment.End,
                )
            }
        }
    }
}

@Composable
fun SwapDetailScreen(
    swapItem: Routes.SwapDetail,
    onBackClick: () -> Unit = {},
    viewModel: SwapsViewModel = hiltViewModel(),
) {
    val app = appViewModel ?: return
    val swaps by viewModel.swaps.collectAsStateWithLifecycle()
    val swap = swaps.find { it.id == swapItem.id }
    val canClaim = swap?.isClaimable == true

    SwapDetailContent(
        swap = swap,
        onBack = onBackClick,
        canClaim = canClaim,
        onClaim = {
            val id = swap?.id ?: return@SwapDetailContent
            viewModel.claimReverseSwap(id) { result ->
                result
                    .onSuccess { txid ->
                        app.toast(
                            type = Toast.ToastType.SUCCESS,
                            title = "Claim broadcast",
                            description = txid,
                        )
                    }
                    .onFailure { e ->
                        app.toast(
                            type = Toast.ToastType.ERROR,
                            title = "Claim failed",
                            description = e.message ?: "Unknown error",
                        )
                    }
            }
        },
    )
}

@Composable
private fun SwapDetailContent(
    swap: BoltzSwap?,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    canClaim: Boolean = false,
    onClaim: () -> Unit = {},
) {
    Scaffold(
        topBar = { AppTopBar(titleText = "Swap Details", onBackClick = onBack) },
        modifier = modifier,
    ) { padding ->
        if (swap == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                BodyS(text = "Loading…")
            }
            return@Scaffold
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(padding),
        ) {
            item {
                InfoCard(header = "Overview") {
                    DetailRow("ID", swap.id)
                    DetailRow("Type", swap.swapType.toString())
                    DetailRow("Status", swap.status.toString())
                    DetailRow("Network", swap.network.toString())
                }
            }
            item {
                InfoCard(header = "Amounts") {
                    DetailRow("Amount", "${swap.amountSat.formatToModernDisplay()} sats")
                    DetailRow(
                        "Onchain amount",
                        swap.onchainAmountSat?.let { "${it.formatToModernDisplay()} sats" } ?: "-",
                    )
                }
            }
            item {
                InfoCard(header = "Addresses") {
                    DetailRow("Lockup", swap.lockupAddress ?: "-")
                    DetailRow("Claim / onchain", swap.onchainAddress ?: "-")
                }
            }
            swap.invoice?.let { invoice ->
                item {
                    InfoCard(header = "Lightning") {
                        DetailRow("Invoice", invoice)
                    }
                }
            }
            item {
                InfoCard(header = "Transactions") {
                    DetailRow("Claim txid", swap.claimTxId ?: "-")
                    DetailRow("Refund txid", swap.refundTxId ?: "-")
                }
            }
            item {
                InfoCard(header = "Recovery") {
                    DetailRow("Swap index", swap.swapIndex.toString())
                    DetailRow("Timeout block", swap.timeoutBlockHeight.toString())
                }
            }
            item {
                InfoCard(header = "Timestamps") {
                    DetailRow("Created", swap.createdAt.formatToString().orEmpty())
                }
            }
            if (canClaim) {
                item {
                    PrimaryButton(text = "Claim now", onClick = onClaim)
                }
            }
        }
    }
}
