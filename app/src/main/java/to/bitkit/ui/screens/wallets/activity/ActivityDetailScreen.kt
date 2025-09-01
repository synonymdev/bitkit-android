package to.bitkit.ui.screens.wallets.activity

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.testTag
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
import to.bitkit.ext.isSent
import to.bitkit.ext.isTransfer
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
import to.bitkit.ui.components.MoneySSB
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.TagButton
import to.bitkit.ui.components.Title
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.screens.wallets.activity.components.ActivityAddTagSheet
import to.bitkit.ui.screens.wallets.activity.components.ActivityIcon
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.sheets.BoostTransactionSheet
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.copyToClipboard
import to.bitkit.ui.utils.getScreenTitleRes
import to.bitkit.utils.Logger
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
    if (item == null) {
        Logger.error("Activity not found")
        return
    }


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
    val isSent = item.isSent()
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
    val isTransfer = item.isTransfer()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
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
                useSwipeToHide = false,
                modifier = Modifier.weight(1f)
            )
            ActivityIcon(activity = item, size = 48.dp) // TODO Display the user avatar when selfSend
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
        if (isSent) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Caption13Up(
                        text = when {
                            isTransfer -> stringResource(R.string.wallet__activity_transfer_to_spending)
                            isSelfSend -> "Sent to myself" // TODO add missing localized text
                            else -> stringResource(R.string.wallet__activity_payment)
                        },
                        color = Colors.White64,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.testTag("ActivityAmount")
                    ) {
                        Icon(
                            painter = when {
                                isTransfer -> painterResource(R.drawable.ic_lightning)
                                else -> painterResource(R.drawable.ic_user)
                            },
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        MoneySSB(sats = paymentValue.toLong())
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                }
                if (fee != null) {
                    Column(modifier = Modifier.weight(1f)) {
                        Caption13Up(
                            text = stringResource(R.string.wallet__activity_fee),
                            color = Colors.White64,
                            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.testTag("ActivityFee")
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_speed_normal),
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            MoneySSB(sats = fee.toLong())
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
                    modifier = Modifier.testTag("ActivityTags")
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
                    .clickableAlpha(
                        onClick = copyToClipboard(message) {
                            onCopy(message)
                        }
                    )
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
                            modifier = Modifier
                                .padding(24.dp)
                                .testTag("InvoiceNote")
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
                    modifier = Modifier
                        .weight(1f)
                        .testTag("ActivityTag")
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
                    modifier = Modifier
                        .weight(1f)
                        .testTag(
                            when {
                                item.isBoosted() -> "BoostedButton"
                                item.canBeBoosted() -> "BoostButton"
                                else -> "BoostDisabled"
                            }
                        )
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
                    modifier = Modifier
                        .weight(1f)
                        .testTag("ActivityTxDetails")
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
                            StatusRow(
                                painterResource(R.drawable.ic_hourglass_simple),
                                stringResource(R.string.wallet__activity_pending),
                                Colors.Purple,
                            )
                        }

                        PaymentState.SUCCEEDED -> {
                            StatusRow(
                                painterResource(R.drawable.ic_lightning_alt),
                                stringResource(R.string.wallet__activity_successful),
                                Colors.Purple,
                            )
                        }

                        PaymentState.FAILED -> {
                            StatusRow(
                                painterResource(R.drawable.ic_x),
                                stringResource(R.string.wallet__activity_failed),
                                Colors.Purple,
                            )
                        }
                    }
                }

                is Activity.Onchain -> {
                    // Default status is confirming
                    var statusIcon = painterResource(R.drawable.ic_hourglass_simple)
                    var statusColor = Colors.Brand
                    var statusText = stringResource(R.string.wallet__activity_confirming)
                    var statusTestTag: String? = null

                    if (item.v1.isTransfer) {
                        val duration = 0 // TODO get transfer duration
                        statusText = stringResource(R.string.wallet__activity_transfer_pending)
                            .replace("{duration}", "$duration")
                        statusTestTag = "StatusTransfer"
                    }

                    if (item.v1.isBoosted) {
                        statusIcon = painterResource(R.drawable.ic_timer_alt)
                        statusColor = Colors.Yellow
                        statusText = stringResource(R.string.wallet__activity_boosting)
                        statusTestTag = "StatusBoosting"
                    }

                    if (item.v1.confirmed) {
                        statusIcon = painterResource(R.drawable.ic_check_circle)
                        statusColor = Colors.Green
                        statusText = stringResource(R.string.wallet__activity_confirmed)
                        statusTestTag = "StatusConfirmed"
                    }

                    if (!item.v1.doesExist) {
                        statusIcon = painterResource(R.drawable.ic_x)
                        statusColor = Colors.Red
                        statusText = stringResource(R.string.wallet__activity_removed)
                    }

                    StatusRow(statusIcon, statusText, statusColor, statusTestTag)
                }
            }
        }
    }
}

@Composable
private fun StatusRow(
    icon: Painter,
    text: String,
    color: Color,
    testTag: String? = null,
) {
    Row {
        Icon(
            painter = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp)
        )
        BodySSB(
            text = text,
            color = color,
            modifier = Modifier
                .padding(start = 4.dp)
                .then(testTag?.let { Modifier.testTag(it) } ?: Modifier)
        )
    }
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
