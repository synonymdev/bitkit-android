package to.bitkit.ui.screens.wallets.activity

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.LightningActivity
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentState
import com.synonym.bitkitcore.PaymentType
import to.bitkit.R
import to.bitkit.ext.canBeBoosted
import to.bitkit.ext.ellipsisMiddle
import to.bitkit.ext.isBoosted
import to.bitkit.ext.rawId
import to.bitkit.ext.toActivityItemDate
import to.bitkit.ext.toActivityItemTime
import to.bitkit.ext.totalValue
import to.bitkit.models.Toast
import to.bitkit.ui.Routes
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BalanceHeaderView
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.TagButton
import to.bitkit.ui.components.Title
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.screens.wallets.activity.components.ActivityAddTagSheet
import to.bitkit.ui.screens.wallets.activity.components.ActivityIcon
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.copyToClipboard
import to.bitkit.ui.utils.getScreenTitleRes
import to.bitkit.viewmodels.ActivityDetailViewModel
import to.bitkit.viewmodels.ActivityListViewModel

@Composable
fun ActivityDetailScreen(
    listViewModel: ActivityListViewModel,
    detailViewModel: ActivityDetailViewModel = hiltViewModel(),
    route: Routes.ActivityDetail,
    onExploreClick: (String) -> Unit,
    onBackClick: () -> Unit,
    onCloseClick: () -> Unit,
) {
    val activities by listViewModel.filteredActivities.collectAsStateWithLifecycle()
    val item = activities?.find { it.rawId() == route.id }
        ?: return

    val app = appViewModel ?: return
    val copyToastTitle = stringResource(R.string.common__copied)

    val tags by detailViewModel.tags.collectAsStateWithLifecycle()
    val boostSheetVisible by detailViewModel.boostSheetVisible.collectAsStateWithLifecycle()
    var showAddTagSheet by remember { mutableStateOf(false) }

    LaunchedEffect(item) {
        detailViewModel.setActivity(item)
    }

    val context = LocalContext.current

    Box(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.background(Colors.Black)
        ) {
            AppTopBar(
                titleText = stringResource(item.getScreenTitleRes()),
                onBackClick = onBackClick,
                actions = { CloseNavIcon(onClick = onCloseClick) },
            )
            ActivityDetailContent(
                item = item,
                tags = tags,
                onRemoveTag = { detailViewModel.removeTag(it) },
                onAddTagClick = { showAddTagSheet = true },
                onExploreClick = onExploreClick,
                onCopy = { text ->
                    app.toast(
                        type = Toast.ToastType.SUCCESS,
                        title = copyToastTitle,
                        description = text.ellipsisMiddle(40)
                    )
                },
                onClickBoost = detailViewModel::onClickBoost
            )
            if (showAddTagSheet) {
                ActivityAddTagSheet(
                    listViewModel = listViewModel,
                    activityViewModel = detailViewModel,
                    onDismiss = { showAddTagSheet = false },
                )
            }
        }

        if (boostSheetVisible) {
            (item as? Activity.Onchain)?.let {
                BoostTransactionSheet(
                    modifier = Modifier.fillMaxWidth(),
                    onDismiss = detailViewModel::onDismissBoostSheet,
                    item = it,
                    onSuccess = {
                        app.toast(
                            type = Toast.ToastType.SUCCESS,
                            title = context.getString(R.string.wallet__boost_success_title),
                            description = context.getString(R.string.wallet__boost_success_msg)
                        )
                        onCloseClick()
                    },
                    onFailure = {
                        app.toast(
                            type = Toast.ToastType.ERROR,
                            title = context.getString(R.string.wallet__boost_error_title),
                            description = context.getString(R.string.wallet__boost_error_msg)
                        )
                        detailViewModel.onDismissBoostSheet()
                    },
                    onMaxFee = {
                        app.toast(
                            type = Toast.ToastType.ERROR,
                            title = context.getString(R.string.wallet__send_fee_error),
                            description = "Unable to increase the fee any further. Otherwise, it will exceed half the current input balance" // TODO CREATE STRING RESOURCE
                        )
                    },
                    onMinFee = {
                        app.toast(
                            type = Toast.ToastType.ERROR,
                            title = context.getString(R.string.wallet__send_fee_error),
                            description = context.getString(R.string.wallet__send_fee_error_min)
                        )
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActivityDetailContent(
    item: Activity,
    tags: List<String>,
    onRemoveTag: (String) -> Unit,
    onAddTagClick: () -> Unit,
    onClickBoost: () -> Unit,
    onExploreClick: (String) -> Unit,
    onCopy: (String) -> Unit,
) {
    val isLightning = item is Activity.Lightning
    val accentColor = if (isLightning) Colors.Purple else Colors.Brand
    val isSent = when (item) {
        is Activity.Lightning -> item.v1.txType == PaymentType.SENT
        is Activity.Onchain -> item.v1.txType == PaymentType.SENT
    }
    val amountPrefix = if (isSent) "-" else "+"
    val timestamp = when (item) {
        is Activity.Lightning -> item.v1.timestamp
        is Activity.Onchain -> when (item.v1.confirmed) {
            true -> item.v1.confirmTimestamp ?: item.v1.timestamp
            else -> item.v1.timestamp
        }
    }
    val paymentValue = when (item) {
        is Activity.Lightning -> item.v1.value
        is Activity.Onchain -> item.v1.value
    }
    val fee = when (item) {
        is Activity.Lightning -> item.v1.fee
        is Activity.Onchain -> item.v1.fee
    }
    val isSelfSend = isSent && paymentValue == 0uL

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // header section: amount + icon
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            BalanceHeaderView(
                sats = item.totalValue().toLong(),
                prefix = amountPrefix,
                showBitcoinSymbol = false,
                forceShowBalance = true,
                modifier = Modifier.weight(1f)
            )
            ActivityIcon(activity = item, size = 48.dp) // TODO Display the user avatar when selfsend
        }

        Spacer(modifier = Modifier.height(16.dp))
        StatusSection(item)
        HorizontalDivider(modifier = Modifier.padding(top = 16.dp))

        // Timestamp section: date and time
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            // Date column
            Column(modifier = Modifier.weight(1f)) {
                Caption13Up(
                    text = stringResource(R.string.wallet__activity_date),
                    color = Colors.White64,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_calendar),
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    BodySSB(text = timestamp.toActivityItemDate())
                }
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
            }

            // Time column
            Column(modifier = Modifier.weight(1f)) {
                Caption13Up(
                    text = stringResource(R.string.wallet__activity_time),
                    color = Colors.White64,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_clock),
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    BodySSB(text = timestamp.toActivityItemTime())
                }
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
            }
        }

        // Fee section for sent transactions
        if (isSent) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Caption13Up(
                        text = if (isSelfSend) {
                            "Sent to myself" // TODO translation
                        } else {
                            stringResource(R.string.wallet__activity_payment)
                        },
                        color = Colors.White64,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_user),
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        BodySSB(text = "$paymentValue")
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                }

                // Fee column if fee exists
                if (fee != null) {
                    Column(modifier = Modifier.weight(1f)) {
                        Caption13Up(
                            text = stringResource(R.string.wallet__activity_fee),
                            color = Colors.White64,
                            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(R.drawable.ic_speed_normal),
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            BodySSB(text = fee.toString())
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider()
                    }
                }
            }
        }

        // Tags section
        if (tags.isNotEmpty()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Caption13Up(
                    text = stringResource(R.string.wallet__tags),
                    color = Colors.White64,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    tags.forEach { tag ->
                        TagButton(
                            text = tag,
                            displayIconClose = true,
                            onClick = { onRemoveTag(tag) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
            }
        }

        // Note section for Lightning payments with message
        if (item is Activity.Lightning && item.v1.message.isNotEmpty()) {
            val message = item.v1.message
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickableAlpha(onClick = copyToClipboard(message) {
                        onCopy(message)
                    })
            ) {
                Caption13Up(
                    text = stringResource(R.string.wallet__activity_invoice_note),
                    color = Colors.White64,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ZigzagDivider()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Colors.White10)
                    ) {
                        Title(
                            text = message,
                            color = Colors.White,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Action buttons
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                PrimaryButton(
                    text = stringResource(R.string.wallet__activity_assign),
                    size = ButtonSize.Small,
                    onClick = { /* TODO: Implement assign functionality */ },
                    enabled = !isSelfSend,
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_user_plus),
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
                PrimaryButton(
                    text = stringResource(R.string.wallet__activity_tag),
                    size = ButtonSize.Small,
                    onClick = onAddTagClick,
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_tag),
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                PrimaryButton(
                    text = stringResource(
                        if (item.isBoosted()) {
                            R.string.wallet__activity_boosted
                        } else {
                            R.string.wallet__activity_boost
                        }
                    ),
                    size = ButtonSize.Small,
                    onClick = onClickBoost,
                    enabled = item.canBeBoosted(),
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_timer_alt),
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
                PrimaryButton(
                    text = stringResource(R.string.wallet__activity_explore),
                    size = ButtonSize.Small,
                    onClick = { onExploreClick(item.rawId()) },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_git_branch),
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun StatusSection(item: Activity) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Caption13Up(
            text = stringResource(R.string.wallet__activity_status),
            color = Colors.White64,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (item) {
                is Activity.Lightning -> {
                    when (item.v1.status) {
                        PaymentState.PENDING -> {
                            StatusIcon(painterResource(R.drawable.ic_hourglass_simple), Colors.Purple)
                            StatusText(stringResource(R.string.wallet__activity_pending), Colors.Purple)
                        }

                        PaymentState.SUCCEEDED -> {
                            StatusIcon(painterResource(R.drawable.ic_lightning_alt), Colors.Purple)
                            StatusText(stringResource(R.string.wallet__activity_successful), Colors.Purple)
                        }

                        PaymentState.FAILED -> {
                            StatusIcon(painterResource(R.drawable.ic_x), Colors.Purple)
                            StatusText(stringResource(R.string.wallet__activity_failed), Colors.Purple)
                        }
                    }
                }

                is Activity.Onchain -> {
                    // Default status is confirming
                    var statusIcon = painterResource(R.drawable.ic_hourglass_simple)
                    var statusColor = Colors.Brand
                    var statusText = stringResource(R.string.wallet__activity_confirming)

                    // TODO: handle isTransfer

                    if (item.v1.isBoosted) {
                        statusIcon = painterResource(R.drawable.ic_timer_alt)
                        statusColor = Colors.Yellow
                        statusText = stringResource(R.string.wallet__activity_boosting)
                    }

                    if (item.v1.confirmed) {
                        statusIcon = painterResource(R.drawable.ic_check_circle)
                        statusColor = Colors.Green
                        statusText = stringResource(R.string.wallet__activity_confirmed)
                    }

                    if (!item.v1.doesExist) {
                        statusIcon = painterResource(R.drawable.ic_x)
                        statusColor = Colors.Red
                        statusText = stringResource(R.string.wallet__activity_removed)
                    }

                    StatusIcon(statusIcon, statusColor)
                    StatusText(statusText, statusColor)
                }
            }
        }
    }
}

@Composable
private fun StatusIcon(
    icon: Painter,
    tint: Color,
) {
    Icon(
        painter = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(16.dp)
    )
}

@Composable
private fun StatusText(
    text: String,
    color: Color,
) {
    BodySSB(
        text = text,
        color = color,
        modifier = Modifier.padding(start = 4.dp)
    )
}

@Composable
private fun ZigzagDivider() {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(12.dp)
    ) {
        val zigzagWidth = 24.dp.toPx()
        val amplitude = size.height
        val width = size.width
        val path = Path()

        path.moveTo(0f, 0f)
        var x = 0f
        while (x < width) {
            path.lineTo(x + zigzagWidth / 2, amplitude)
            path.lineTo((x + zigzagWidth).coerceAtMost(width), 0f)
            x += zigzagWidth
        }
        path.lineTo(width, amplitude)
        path.lineTo(0f, amplitude)
        path.close()

        drawPath(
            path = path,
            color = Colors.White10,
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewLightningSent() {
    AppThemeSurface {
        ActivityDetailContent(
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
                    preimage = null,
                    createdAt = null,
                    updatedAt = null,
                )
            ),
            tags = listOf("Lunch", "Drinks"),
            onRemoveTag = {},
            onAddTagClick = {},
            onExploreClick = {},
            onCopy = {},
            onClickBoost = {}
        )
    }
}

@Preview(showSystemUi = true, showBackground = true)
@Composable
private fun PreviewOnchain() {
    AppThemeSurface {
        ActivityDetailContent(
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
                )
            ),
            tags = emptyList(),
            onRemoveTag = {},
            onAddTagClick = {},
            onExploreClick = {},
            onCopy = {},
            onClickBoost = {},
        )
    }
}
