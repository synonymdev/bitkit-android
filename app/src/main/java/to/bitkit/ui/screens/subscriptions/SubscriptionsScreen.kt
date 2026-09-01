@file:OptIn(ExperimentalTime::class)

package to.bitkit.ui.screens.subscriptions

import androidx.annotation.DrawableRes
import androidx.annotation.RawRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.rememberLottieComposition
import com.synonym.paykit.PaymentRequestLifecycleState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ext.dateTimeFormatterOf
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.models.PubkyProfile
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.models.safe
import to.bitkit.repositories.PaykitPaymentRequestId
import to.bitkit.repositories.PaykitRecurrenceUnit
import to.bitkit.repositories.PaykitSubscription
import to.bitkit.repositories.PaykitSubscriptionId
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.FillWidth
import to.bitkit.ui.components.MoneyCell
import to.bitkit.ui.components.MoneyDisplay
import to.bitkit.ui.components.MoneyMSB
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.PubkyContactAvatar
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.Sheet
import to.bitkit.ui.components.SubscriptionRoute
import to.bitkit.ui.components.SwipeToConfirm
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.rememberMoneyText
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.screens.paymentrequests.PaymentRequestCard
import to.bitkit.ui.screens.paymentrequests.PaymentRequestsScreen
import to.bitkit.ui.screens.wallets.activity.components.CustomTabRowWithSpacing
import to.bitkit.ui.screens.wallets.activity.components.TabItem
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.removeAccentTags
import to.bitkit.ui.utils.withAccent
import to.bitkit.viewmodels.AppViewModel
import java.time.ZoneId
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@Composable
fun SubscriptionsScreen(
    appViewModel: AppViewModel,
    onBack: () -> Unit,
    onRequestPayment: () -> Unit,
    onDetails: (PaykitSubscriptionId) -> Unit,
    onPaymentRequestDetails: (PaykitPaymentRequestId) -> Unit,
    showPayments: Boolean = false,
) {
    val subscriptions by appViewModel.subscriptions.collectAsStateWithLifecycle()
    val contacts by appViewModel.pubkyContacts.collectAsStateWithLifecycle()
    val pendingPaymentRequests by appViewModel.pendingPaymentRequests.collectAsStateWithLifecycle()
    val now = rememberSubscriptionNow(subscriptions)

    SubscriptionsContent(
        subscriptions = subscriptions.toImmutableList(),
        contacts = contacts.toImmutableList(),
        acceptedAt = appViewModel::subscriptionAcceptedAt,
        now = now,
        onBack = onBack,
        initialTab = if (showPayments) SubscriptionTab.Payments else SubscriptionTab.Overview,
        pendingPaymentRequestCount = pendingPaymentRequests.size,
        onSubscription = { subscription ->
            if (subscription.isProposalVisible(now)) {
                appViewModel.showSheet(Sheet.Subscription(SubscriptionRoute.Review(subscription.id)))
            } else {
                onDetails(subscription.id)
            }
        },
        paymentsContent = {
            PaymentRequestsScreen(
                appViewModel = appViewModel,
                onBack = onBack,
                onRequestPayment = onRequestPayment,
                onDetails = onPaymentRequestDetails,
                showsNavigationBar = false,
            )
        },
    )
}

@Composable
internal fun SubscriptionsContent(
    subscriptions: ImmutableList<PaykitSubscription>,
    contacts: ImmutableList<PubkyProfile>,
    acceptedAt: (PaykitSubscriptionId) -> Instant?,
    now: Instant,
    onBack: () -> Unit,
    initialTab: SubscriptionTab,
    pendingPaymentRequestCount: Int,
    onSubscription: (PaykitSubscription) -> Unit,
    paymentsContent: @Composable () -> Unit,
) {
    val proposals = subscriptions.filter { it.isProposalVisible(now) }
    val active = subscriptions.filter { it.isActive(now) }
    val expired = subscriptions.filter { it.isExpired(now) && acceptedAt(it.id) != null }
    val hasVisibleSubscriptions = proposals.isNotEmpty() || active.isNotEmpty() || expired.isNotEmpty()
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(initialTab.ordinal) }
    val selectedTab = SubscriptionTab.entries[selectedTabIndex]

    Column(
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .testTag("SubscriptionsScreen")
    ) {
        AppTopBar(
            titleText = stringResource(R.string.subscriptions__title),
            onBackClick = onBack,
            actions = { DrawerNavIcon() },
        )
        SubscriptionTabs(
            selectedTab = selectedTab,
            pendingPaymentRequestCount = pendingPaymentRequestCount,
            onTabChange = { selectedTabIndex = it.ordinal },
        )

        if (selectedTab == SubscriptionTab.Payments) {
            Box(Modifier.weight(1f)) {
                paymentsContent()
            }
        } else if (!hasVisibleSubscriptions) {
            SubscriptionEmptyState(Modifier.weight(1f))
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(32.dp),
                modifier = Modifier.weight(1f),
            ) {
                item {
                    SubscriptionMetrics(
                        dueSats = dueThisMonth(
                            subscriptions.filter { it.lifecycleState == PaymentRequestLifecycleState.ACTIVE_RECURRING },
                            acceptedAt,
                            now,
                        ),
                        activeCount = active.size,
                    )
                }
                subscriptionSection(
                    titleRes = R.string.subscriptions__proposals,
                    subscriptions = proposals,
                    contacts = contacts,
                    now = now,
                    onSubscription = onSubscription,
                )
                subscriptionSection(
                    titleRes = R.string.subscriptions__active,
                    subscriptions = active,
                    contacts = contacts,
                    now = now,
                    onSubscription = onSubscription,
                )
                subscriptionSection(
                    titleRes = R.string.subscriptions__expired,
                    subscriptions = expired,
                    contacts = contacts,
                    now = now,
                    onSubscription = onSubscription,
                )
            }
        }
    }
}

private fun LazyListScope.subscriptionSection(
    @StringRes titleRes: Int,
    subscriptions: List<PaykitSubscription>,
    contacts: ImmutableList<PubkyProfile>,
    now: Instant,
    onSubscription: (PaykitSubscription) -> Unit,
) {
    if (subscriptions.isEmpty()) return
    item(key = "subscription-section-$titleRes") {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Caption13Up(text = stringResource(titleRes), color = Colors.White64)
            subscriptions.forEach { subscription ->
                SubscriptionRow(
                    subscription = subscription,
                    contact = contacts.contactFor(subscription),
                    now = now,
                    faded = subscription.isExpired(now),
                    onClick = { onSubscription(subscription) },
                )
            }
        }
    }
}

@Composable
private fun SubscriptionTabs(
    selectedTab: SubscriptionTab,
    pendingPaymentRequestCount: Int,
    onTabChange: (SubscriptionTab) -> Unit,
) {
    CustomTabRowWithSpacing(
        tabs = persistentListOf(SubscriptionTab.Overview, SubscriptionTab.Payments),
        currentTabIndex = selectedTab.ordinal,
        selectedColor = Colors.White,
        onTabChange = onTabChange,
        badgeCount = { tab -> pendingPaymentRequestCount.takeIf { tab == SubscriptionTab.Payments } },
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

internal enum class SubscriptionTab : TabItem {
    Overview,
    Payments;

    override val uiText: String
        @Composable get() = stringResource(
            when (this) {
                Overview -> R.string.subscriptions__overview
                Payments -> R.string.subscriptions__payments
            }
        )
}

@Composable
private fun SubscriptionEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 24.dp)
    ) {
        FillHeight()
        Image(
            painter = painterResource(R.drawable.subscription_clock),
            contentDescription = null,
            modifier = Modifier
                .size(256.dp)
                .align(Alignment.CenterHorizontally),
        )
        FillHeight()
        Display(
            text = stringResource(R.string.subscriptions__empty_headline).withAccent(accentColor = Colors.Purple),
        )
        VerticalSpacer(12.dp)
        BodyM(text = stringResource(R.string.subscriptions__empty_description), color = Colors.White64)
    }
}

@Composable
private fun SubscriptionMetrics(dueSats: Long, activeCount: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Caption13Up(text = stringResource(R.string.subscriptions__due_this_month), color = Colors.White64)
            VerticalSpacer(8.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_calendar), contentDescription = null, tint = Colors.Purple)
                MoneyMSB(sats = dueSats)
            }
        }
        Spacer(Modifier.size(width = 1.dp, height = 50.dp).background(Colors.White16))
        Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
            Caption13Up(text = stringResource(R.string.subscriptions__active), color = Colors.White64)
            VerticalSpacer(8.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_arrows_clockwise), contentDescription = null, tint = Colors.Purple)
                BodyMSB(text = activeCount.toString())
            }
        }
    }
}

@Composable
private fun SubscriptionRow(
    subscription: PaykitSubscription,
    contact: PubkyProfile,
    now: Instant,
    faded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (faded) 0.5f else 1f)
            .clip(RoundedCornerShape(16.dp))
            .background(Colors.Gray6)
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        PubkyContactAvatar(profile = contact, size = 40.dp)
        Column(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
            BodyMSB(
                text = subscription.note ?: stringResource(R.string.subscriptions__subscription),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BodyS(
                text = subscription.rowSubtitle(now),
                color = Colors.White64,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MoneyCell(sats = subscription.displaySats)
    }
}

@Composable
fun SubscriptionDetailScreen(
    appViewModel: AppViewModel,
    id: PaykitSubscriptionId,
    onBack: () -> Unit,
) {
    val subscriptions by appViewModel.subscriptions.collectAsStateWithLifecycle()
    val contacts by appViewModel.pubkyContacts.collectAsStateWithLifecycle()
    val paymentHistory by appViewModel.paymentRequestHistory.collectAsStateWithLifecycle()
    val subscription = subscriptions.firstOrNull { it.id == id }
    val now = rememberSubscriptionNow(listOfNotNull(subscription))

    Column(
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
    ) {
        AppTopBar(
            titleText = subscription?.note ?: stringResource(R.string.subscriptions__subscription),
            onBackClick = onBack,
            actions = { DrawerNavIcon() },
        )
        if (subscription == null) {
            FillHeight()
            BodyM(
                text = stringResource(R.string.subscriptions__unavailable),
                color = Colors.White64,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            FillHeight()
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp),
            modifier = Modifier
                .weight(1f)
                .alpha(if (subscription.isExpired(now)) 0.5f else 1f),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Caption13Up(text = subscription.cadenceText(), color = Colors.White64)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        MoneyDisplay(sats = subscription.displaySats, showSymbol = true)
                        FillWidth()
                        PubkyContactAvatar(profile = contacts.contactFor(subscription), size = 48.dp)
                    }
                }
            }
            item { SubscriptionDetailsGrid(subscription, now) }
            val payments = paymentHistory.filter { it.belongsTo(subscription) }
            if (payments.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Caption13Up(text = stringResource(R.string.subscriptions__payments), color = Colors.White64)
                        payments.forEach { payment ->
                            PaymentRequestCard(
                                request = payment,
                                contact = contacts.contactFor(subscription),
                                compactSubtitle = subscription.note?.takeIf(String::isNotBlank)
                                    ?: stringResource(R.string.subscriptions__subscription),
                                isOutgoingPayment = true,
                            )
                        }
                    }
                }
            }
        }
        SubscriptionDetailFooter(subscription, appViewModel, now)
    }
}

@Composable
private fun SubscriptionDetailsGrid(subscription: PaykitSubscription, now: Instant) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SubscriptionDetailCell(
                stringResource(R.string.subscriptions__subscription),
                subscription.note ?: stringResource(R.string.subscriptions__subscription),
                R.drawable.ic_cube,
                Modifier.weight(1f),
            )
            SubscriptionDetailCell(
                stringResource(R.string.subscriptions__frequency),
                subscription.frequencyValue(),
                R.drawable.ic_arrows_clockwise,
                Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SubscriptionDetailCell(
                stringResource(R.string.subscriptions__status),
                if (subscription.isActive(now)) {
                    stringResource(R.string.subscriptions__active)
                } else {
                    stringResource(R.string.subscriptions__expired)
                },
                R.drawable.ic_check,
                Modifier.weight(1f),
            )
            if (subscription.shouldShowTiming(now)) {
                SubscriptionDetailCell(
                    subscription.timingTitle(now),
                    subscription.renewalText(now),
                    R.drawable.ic_calendar,
                    Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SubscriptionDetailCell(
    title: String,
    value: String,
    @DrawableRes iconRes: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .height(68.dp)
    ) {
        Caption13Up(text = title, color = Colors.White64)
        VerticalSpacer(8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = Colors.Purple,
                modifier = Modifier.size(16.dp),
            )
            BodySSB(text = value, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        FillHeight()
        HorizontalDivider(color = Colors.White10)
    }
}

@Composable
private fun SubscriptionDetailFooter(
    subscription: PaykitSubscription,
    appViewModel: AppViewModel,
    now: Instant,
) {
    val hasMoreInfo = subscription.metadata.description != null || subscription.metadata.benefits.isNotEmpty()
    val canCancel = subscription.canCancel(now)
    if (!hasMoreInfo && !canCancel) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        if (hasMoreInfo) {
            SecondaryButton(
                text = stringResource(R.string.subscriptions__more_info),
                onClick = { appViewModel.showSheet(Sheet.Subscription(SubscriptionRoute.Details(subscription.id))) },
                modifier = Modifier.weight(1f),
            )
        }
        if (canCancel) {
            PrimaryButton(
                text = stringResource(R.string.subscriptions__cancel),
                onClick = { appViewModel.showSheet(Sheet.Subscription(SubscriptionRoute.Cancel(subscription.id))) },
                modifier = Modifier.weight(1f),
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_x),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
    }
}

@Composable
fun SubscriptionSheet(appViewModel: AppViewModel, initialRoute: SubscriptionRoute) {
    var route by remember(initialRoute) { mutableStateOf(initialRoute) }
    var previousRoute by remember(initialRoute) { mutableStateOf<SubscriptionRoute?>(null) }
    var isProcessing by remember(initialRoute) { mutableStateOf(false) }
    val subscriptions by appViewModel.subscriptions.collectAsStateWithLifecycle()
    val isAccepting by appViewModel.isAcceptingSubscription.collectAsStateWithLifecycle()
    val subscription = subscriptions.firstOrNull { it.id == route.id }
    val contacts by appViewModel.pubkyContacts.collectAsStateWithLifecycle()
    val now = rememberSubscriptionNow(listOfNotNull(subscription))

    LaunchedEffect(route, subscription, isProcessing) {
        val proposalIsUnavailable = route is SubscriptionRoute.Review &&
            subscription?.isProposalVisible(now) != true
        if (!isProcessing && proposalIsUnavailable) {
            appViewModel.hideSheet()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .sheetHeight()
            .gradientBackground()
    ) {
        if (subscription == null) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                SheetTopBar(titleText = stringResource(R.string.subscriptions__subscription))
                FillHeight()
                BodyM(stringResource(R.string.subscriptions__unavailable), color = Colors.White64)
                FillHeight()
                PrimaryButton(text = stringResource(R.string.common__close), onClick = appViewModel::hideSheet)
                VerticalSpacer(16.dp)
            }
        } else {
            val payOnAcceptance = subscription.paymentDueOnAcceptance(now) != null
            when (route) {
                is SubscriptionRoute.Review -> SubscriptionReview(
                    subscription = subscription,
                    payOnAcceptance = payOnAcceptance,
                    now = now,
                    contact = contacts.contactFor(subscription),
                    onDetails = {
                        if (!isAccepting) {
                            previousRoute = route
                            route = SubscriptionRoute.Details(subscription.id)
                        }
                    },
                    onSubscribe = {
                        isProcessing = true
                        appViewModel.acceptSubscriptionAndStartPayment(subscription).fold(
                            onSuccess = { startedPayment ->
                                if (startedPayment) {
                                    true
                                } else {
                                    route = SubscriptionRoute.Success(subscription.id)
                                    isProcessing = false
                                    true
                                }
                            },
                            onFailure = {
                                isProcessing = false
                                false
                            },
                        )
                    },
                )
                is SubscriptionRoute.Success -> SubscriptionSuccess(
                    onClose = appViewModel::hideSheet,
                    paymentType = null,
                )
                is SubscriptionRoute.Details -> SubscriptionMoreInfo(
                    subscription = subscription,
                    contact = contacts.contactFor(subscription),
                    onBack = {
                        if (previousRoute != null) {
                            route = requireNotNull(previousRoute)
                            previousRoute = null
                        } else {
                            appViewModel.hideSheet()
                        }
                    },
                    onClose = appViewModel::hideSheet,
                )
                is SubscriptionRoute.Cancel -> SubscriptionCancel(
                    subscription = subscription,
                    contact = contacts.contactFor(subscription),
                    onDetails = {
                        previousRoute = route
                        route = SubscriptionRoute.Details(subscription.id)
                    },
                    onCancel = {
                        appViewModel.cancelSubscription(subscription.id)
                            .onSuccess { appViewModel.hideSheet() }
                            .isSuccess
                    },
                )
            }
        }
    }
}

private val SubscriptionRoute.id: PaykitSubscriptionId
    get() = when (this) {
        is SubscriptionRoute.Review -> id
        is SubscriptionRoute.Success -> id
        is SubscriptionRoute.Details -> id
        is SubscriptionRoute.Cancel -> id
    }

@Composable
private fun SubscriptionReview(
    subscription: PaykitSubscription,
    contact: PubkyProfile,
    payOnAcceptance: Boolean,
    now: Instant,
    onDetails: () -> Unit,
    onSubscribe: suspend () -> Boolean,
) {
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        SheetTopBar(titleText = stringResource(R.string.subscriptions__review_and_subscribe))
        rememberMoneyText(sats = subscription.displaySats, reversed = true, showSymbol = true)?.let {
            Caption13Up(text = it.removeAccentTags(), color = Colors.White64)
        }
        MoneyDisplay(sats = subscription.displaySats, showSymbol = true)
        VerticalSpacer(24.dp)
        SubscriptionProviderCard(subscription, contact, onClick = onDetails)
        if (!subscription.recurrence.unit.isSupported) {
            VerticalSpacer(16.dp)
            BodyM(text = stringResource(R.string.subscriptions__unsupported_description), color = Colors.White64)
        } else if (subscription.acceptedPaymentEndpointIdentifiers.isEmpty()) {
            VerticalSpacer(16.dp)
            BodyM(
                text = stringResource(R.string.subscriptions__unsupported_payment_description),
                color = Colors.White64,
            )
        }
        FillHeight()
        Image(
            painter = painterResource(R.drawable.subscription_clock),
            contentDescription = null,
            modifier = Modifier.size(256.dp).align(Alignment.CenterHorizontally),
        )
        FillHeight()
        if (subscription.isProposalActionable(now)) {
            SwipeToConfirm(
                text = stringResource(
                    if (payOnAcceptance) {
                        R.string.subscriptions__swipe_to_subscribe_and_pay
                    } else {
                        R.string.subscriptions__swipe_to_subscribe
                    }
                ),
                color = Colors.Purple,
                loading = loading,
                onConfirm = {
                    loading = true
                    scope.launch {
                        if (!onSubscribe()) loading = false
                    }
                },
            )
        }
        VerticalSpacer(16.dp)
    }
}

@Composable
private fun SubscriptionProviderCard(
    subscription: PaykitSubscription,
    contact: PubkyProfile,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val displayedSubtitle = subtitle ?: subscription.subscriptionFrequencyText()
    val cardModifier = if (onClick == null) {
        Modifier.fillMaxWidth()
    } else {
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Colors.Gray6)
            .clickable(onClick = onClick)
            .padding(16.dp)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = cardModifier,
    ) {
        PubkyContactAvatar(profile = contact, size = 40.dp)
        Column(Modifier.padding(start = 16.dp).weight(1f)) {
            BodyMSB(
                text = subscription.note ?: stringResource(R.string.subscriptions__subscription),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BodyS(
                text = displayedSubtitle,
                color = Colors.White64,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onClick != null) {
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = Colors.White64)
        }
    }
}

@Composable
fun SubscriptionSuccess(
    onClose: () -> Unit,
    paymentType: NewTransactionSheetType?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
    ) {
        val composition by rememberLottieComposition(
            LottieCompositionSpec.RawRes(subscriptionConfettiResource(paymentType))
        )
        LottieAnimation(
            composition = composition,
            contentScale = ContentScale.Crop,
            iterations = 100,
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            SheetTopBar(titleText = stringResource(R.string.subscriptions__subscribed))
            FillHeight()
            Image(
                painter = painterResource(R.drawable.check),
                contentDescription = null,
                modifier = Modifier.size(256.dp).align(Alignment.CenterHorizontally),
            )
            FillHeight()
            PrimaryButton(text = stringResource(R.string.common__close), onClick = onClose)
            VerticalSpacer(16.dp)
        }
    }
}

@RawRes
internal fun subscriptionConfettiResource(paymentType: NewTransactionSheetType?): Int =
    if (paymentType == NewTransactionSheetType.ONCHAIN) R.raw.confetti_orange else R.raw.confetti_purple

@Composable
private fun SubscriptionMoreInfo(
    subscription: PaykitSubscription,
    contact: PubkyProfile,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        SheetTopBar(titleText = stringResource(R.string.subscriptions__details), onBack = onBack)
        SubscriptionProviderCard(subscription, contact)
        VerticalSpacer(24.dp)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.weight(1f)) {
            subscription.metadata.description?.let { item { BodySSB(it) } }
            items(subscription.metadata.benefits) { benefit ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BodySSB("•")
                    BodySSB(benefit)
                }
            }
        }
        PrimaryButton(text = stringResource(R.string.common__ok), onClick = onClose)
        VerticalSpacer(16.dp)
    }
}

@Composable
private fun SubscriptionCancel(
    subscription: PaykitSubscription,
    contact: PubkyProfile,
    onDetails: () -> Unit,
    onCancel: suspend () -> Boolean,
) {
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        SheetTopBar(titleText = stringResource(R.string.subscriptions__cancel_subscription))
        rememberMoneyText(sats = subscription.displaySats, reversed = true, showSymbol = true)?.let {
            Caption13Up(text = it.removeAccentTags(), color = Colors.White64)
        }
        MoneyDisplay(sats = subscription.displaySats, showSymbol = true)
        VerticalSpacer(24.dp)
        SubscriptionProviderCard(
            subscription = subscription,
            contact = contact,
            subtitle = subscription.rowSubtitle(Clock.System.now()),
            onClick = onDetails,
        )
        FillHeight()
        Image(
            painter = painterResource(R.drawable.cross),
            contentDescription = null,
            modifier = Modifier
                .size(256.dp)
                .align(Alignment.CenterHorizontally),
        )
        FillHeight()
        SwipeToConfirm(
            text = stringResource(R.string.subscriptions__swipe_to_cancel),
            color = Colors.Red,
            loading = loading,
            onConfirm = {
                loading = true
                scope.launch {
                    if (!onCancel()) loading = false
                }
            },
        )
        VerticalSpacer(16.dp)
    }
}

private fun List<PubkyProfile>.contactFor(subscription: PaykitSubscription): PubkyProfile =
    firstOrNull { PubkyPublicKeyFormat.matches(it.publicKey, subscription.counterparty) }
        ?: PubkyProfile.placeholder(subscription.counterparty)

@Composable
private fun PaykitSubscription.cadenceText(): String = when (recurrence.unit) {
    PaykitRecurrenceUnit.Day -> if (recurrence.every == 1) {
        stringResource(R.string.subscriptions__per_day)
    } else {
        stringResource(R.string.subscriptions__every_days, recurrence.every)
    }
    PaykitRecurrenceUnit.Week -> if (recurrence.every == 1) {
        stringResource(R.string.subscriptions__per_week)
    } else {
        stringResource(R.string.subscriptions__every_weeks, recurrence.every)
    }
    PaykitRecurrenceUnit.Month -> if (recurrence.every == 1) {
        stringResource(R.string.subscriptions__per_month)
    } else {
        stringResource(R.string.subscriptions__every_months, recurrence.every)
    }
    PaykitRecurrenceUnit.Year -> if (recurrence.every == 1) {
        stringResource(R.string.subscriptions__per_year)
    } else {
        stringResource(R.string.subscriptions__every_years, recurrence.every)
    }
    PaykitRecurrenceUnit.Minute, PaykitRecurrenceUnit.Hour ->
        stringResource(R.string.subscriptions__unsupported_frequency)
}

@Composable
private fun PaykitSubscription.frequencyValue(): String {
    if (recurrence.every != 1) return cadenceText()
    return when (recurrence.unit) {
        PaykitRecurrenceUnit.Day -> stringResource(R.string.subscriptions__daily)
        PaykitRecurrenceUnit.Week -> stringResource(R.string.subscriptions__weekly)
        PaykitRecurrenceUnit.Month -> stringResource(R.string.subscriptions__monthly)
        PaykitRecurrenceUnit.Year -> stringResource(R.string.subscriptions__yearly)
        PaykitRecurrenceUnit.Minute, PaykitRecurrenceUnit.Hour ->
            stringResource(R.string.subscriptions__unsupported_frequency)
    }
}

@Composable
private fun PaykitSubscription.subscriptionFrequencyText(): String {
    if (recurrence.every != 1) return cadenceText()
    return when (recurrence.unit) {
        PaykitRecurrenceUnit.Day -> stringResource(R.string.subscriptions__daily_subscription)
        PaykitRecurrenceUnit.Week -> stringResource(R.string.subscriptions__weekly_subscription)
        PaykitRecurrenceUnit.Month -> stringResource(R.string.subscriptions__monthly_subscription)
        PaykitRecurrenceUnit.Year -> stringResource(R.string.subscriptions__yearly_subscription)
        PaykitRecurrenceUnit.Minute, PaykitRecurrenceUnit.Hour ->
            stringResource(R.string.subscriptions__unsupported_frequency)
    }
}

@Composable
private fun PaykitSubscription.rowSubtitle(now: Instant): String = when {
    isProposalVisible(now) || !recurrence.unit.isSupported -> subscriptionFrequencyText()
    isExpired(now) -> recurrence.endsAt?.let {
        stringResource(R.string.subscriptions__expires_date, it.formatShortDate())
    } ?: stringResource(R.string.subscriptions__expired)
    recurrence.endsAt != null -> stringResource(
        R.string.subscriptions__expires_date,
        recurrence.endsAt.formatShortDate(),
    )
    else -> {
        val renewal = recurrence.nextPeriodAfter(now)?.startsAt
        if (renewal == null) {
            subscriptionFrequencyText()
        } else {
            stringResource(
                R.string.subscriptions__renews_date,
                renewal.formatShortDate(),
            )
        }
    }
}

internal fun PaykitSubscription.shouldShowTiming(now: Instant): Boolean =
    isActive(now) || recurrence.endsAt != null

internal fun PaykitSubscription.canCancel(now: Instant): Boolean =
    isActive(now) && recurrence.endsAt == null

@Composable
private fun PaykitSubscription.timingTitle(now: Instant): String = when {
    !isActive(now) -> stringResource(R.string.subscriptions__expired)
    recurrence.endsAt == null -> stringResource(R.string.subscriptions__renews)
    else -> stringResource(R.string.subscriptions__expires)
}

@Composable
private fun PaykitSubscription.renewalText(now: Instant): String =
    (recurrence.endsAt ?: recurrence.nextPeriodAfter(now)?.startsAt)?.formatFullDate()
        ?: stringResource(R.string.subscriptions__ongoing)

@Composable
private fun rememberSubscriptionNow(subscriptions: List<PaykitSubscription>): Instant {
    var now by remember(subscriptions) { mutableStateOf(Clock.System.now()) }
    LaunchedEffect(subscriptions, now) {
        val nextTransition = nextSubscriptionTransition(subscriptions, now) ?: return@LaunchedEffect
        delay(nextTransition - now)
        now = Clock.System.now()
    }
    return now
}

internal fun nextSubscriptionTransition(
    subscriptions: List<PaykitSubscription>,
    now: Instant,
    zoneId: ZoneId = ZoneId.systemDefault(),
): Instant? {
    val activeSubscriptions = subscriptions.filter { it.isActive(now) }
    val dates = subscriptions.flatMap {
        listOf(it.recurrence.startsAt, it.proposalExpiresAt, it.recurrence.endsAt)
    }.filterNotNull().toMutableList()
    dates += activeSubscriptions.mapNotNull { it.recurrence.nextPeriodAfter(now)?.startsAt }
    if (activeSubscriptions.isNotEmpty()) {
        val nextMonth = java.time.Instant.ofEpochMilli(now.toEpochMilliseconds())
            .atZone(zoneId)
            .toLocalDate()
            .withDayOfMonth(1)
            .plusMonths(1)
            .atStartOfDay(zoneId)
            .toInstant()
        dates += Instant.fromEpochMilliseconds(nextMonth.toEpochMilli())
    }
    return dates.filter { it > now }.minOrNull()
}

private fun Instant.formatShortDate(): String = dateTimeFormatterOf("MMMM d")
    .format(java.time.Instant.ofEpochMilli(toEpochMilliseconds()))

private fun Instant.formatFullDate(): String = dateTimeFormatterOf("MMMM d, yyyy")
    .format(java.time.Instant.ofEpochMilli(toEpochMilliseconds()))

private val PaykitSubscription.displaySats: Long
    get() = amountSats.coerceAtMost(Long.MAX_VALUE.toULong()).toLong()

private fun dueThisMonth(
    subscriptions: List<PaykitSubscription>,
    acceptedAt: (PaykitSubscriptionId) -> Instant?,
    now: Instant,
): Long {
    val zonedNow = java.time.Instant.ofEpochMilli(now.toEpochMilliseconds()).atZone(ZoneId.systemDefault())
    val start = zonedNow.withDayOfMonth(1).toLocalDate().atStartOfDay(zonedNow.zone).toInstant()
    val end = zonedNow.plusMonths(1).withDayOfMonth(1).toLocalDate().atStartOfDay(zonedNow.zone).toInstant()
    val startInstant = Instant.fromEpochMilliseconds(start.toEpochMilli())
    val endInstant = Instant.fromEpochMilliseconds(end.toEpochMilli())
    val total = subscriptions.fold(0uL) { total, subscription ->
        val acceptance = acceptedAt(subscription.id) ?: return@fold total
        val count = subscription.recurrence.periodsThrough(endInstant, acceptance).count {
            it.startsAt >= startInstant && it.startsAt < endInstant && it !in subscription.paidPeriods
        }
        val subtotal = subscription.amountSats.safe() * count.toULong().safe()
        total.safe() + subtotal.safe()
    }
    return total.coerceAtMost(Long.MAX_VALUE.toULong()).toLong()
}
