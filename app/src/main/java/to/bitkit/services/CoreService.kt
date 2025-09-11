package to.bitkit.services

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.ActivityFilter
import com.synonym.bitkitcore.BtOrderState2
import com.synonym.bitkitcore.CJitStateEnum
import com.synonym.bitkitcore.CreateCjitOptions
import com.synonym.bitkitcore.CreateOrderOptions
import com.synonym.bitkitcore.FeeRates
import com.synonym.bitkitcore.GetAddressResponse
import com.synonym.bitkitcore.GetAddressesResponse
import com.synonym.bitkitcore.IBtEstimateFeeResponse2
import com.synonym.bitkitcore.IBtInfo
import com.synonym.bitkitcore.IBtOrder
import com.synonym.bitkitcore.IcJitEntry
import com.synonym.bitkitcore.LightningActivity
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentState
import com.synonym.bitkitcore.PaymentType
import com.synonym.bitkitcore.SortDirection
import com.synonym.bitkitcore.WordCount
import com.synonym.bitkitcore.addTags
import com.synonym.bitkitcore.createCjitEntry
import com.synonym.bitkitcore.createOrder
import com.synonym.bitkitcore.deleteActivityById
import com.synonym.bitkitcore.estimateOrderFeeFull
import com.synonym.bitkitcore.getActivities
import com.synonym.bitkitcore.getActivityById
import com.synonym.bitkitcore.getAllUniqueTags
import com.synonym.bitkitcore.getCjitEntries
import com.synonym.bitkitcore.getInfo
import com.synonym.bitkitcore.getOrders
import com.synonym.bitkitcore.getTags
import com.synonym.bitkitcore.initDb
import com.synonym.bitkitcore.insertActivity
import com.synonym.bitkitcore.openChannel
import com.synonym.bitkitcore.refreshActiveCjitEntries
import com.synonym.bitkitcore.refreshActiveOrders
import com.synonym.bitkitcore.removeTags
import com.synonym.bitkitcore.updateActivity
import com.synonym.bitkitcore.updateBlocktankUrl
import com.synonym.bitkitcore.upsertActivity
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.lightningdevkit.ldknode.ConfirmationStatus
import org.lightningdevkit.ldknode.Network
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
import to.bitkit.async.ServiceQueue
import to.bitkit.data.CacheStore
import to.bitkit.env.Env
import to.bitkit.ext.amountSats
import to.bitkit.models.LnPeer
import to.bitkit.models.toCoreNetwork
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

// region Core

@Singleton
class CoreService @Inject constructor(
    private val lightningService: LightningService,
    private val httpClient: HttpClient,
    private val cacheStore: CacheStore,
) {
    private var walletIndex: Int = 0

    val activity: ActivityService by lazy { ActivityService(coreService = this, cacheStore = cacheStore) }
    val blocktank: BlocktankService by lazy {
        BlocktankService(
            coreService = this,
            lightningService = lightningService,
        )
    }
    val onchain: OnchainService by lazy { OnchainService() }

    init {
        init()
    }

    private fun init(walletIndex: Int = 0) {
        this.walletIndex = walletIndex

        // Block queue until the init completes forcing any additional calls to wait for it
        ServiceQueue.CORE.blocking {
            try {
                val result = initDb(basePath = Env.bitkitCoreStoragePath(walletIndex))
                Logger.info("bitkit-core database init: $result")
            } catch (e: Exception) {
                Logger.error("bitkit-core database init failed", e)
            }

            try {
                val blocktankUrl = Env.blocktankApiUrl
                updateBlocktankUrl(newUrl = blocktankUrl)
                Logger.info("Blocktank URL updated to: $blocktankUrl")
            } catch (e: Exception) {
                Logger.error("Failed to update Blocktank URL", e)
            }
        }
    }

    /**
     * Returns true if geo blocked, false if allowed, null if unable to check
     */
    @Suppress("InstanceOfCheckForException", "TooGenericExceptionCaught")
    private suspend fun isGeoBlocked(): Boolean? {
        return try {
            ServiceQueue.CORE.background {
                Logger.verbose("Checking geo status…", context = "GeoCheck")
                val response = httpClient.get(Env.geoCheckUrl)

                when (response.status.value) {
                    HttpStatusCode.OK.value -> {
                        Logger.verbose("Region allowed", context = "GeoCheck")
                        false
                    }

                    HttpStatusCode.Forbidden.value -> {
                        Logger.warn("Region blocked", context = "GeoCheck")
                        true
                    }

                    else -> {
                        Logger.warn("Unexpected status code: ${response.status.value}", context = "GeoCheck")
                        null
                    }
                }
            }
        } catch (e: Exception) {
            // Handle network failures gracefully
            val isNetworkError = e is UnknownHostException ||
                e is SocketTimeoutException ||
                e is ConnectException ||
                e.message?.contains("Unable to resolve host") == true ||
                e.message?.contains("No address associated with hostname") == true

            if (isNetworkError) {
                Logger.warn(
                    "Network error during geo check, unable to determine status: ${e.message}",
                    context = "GeoCheck"
                )
                null // Return null when network is unavailable
            } else {
                Logger.error("Unexpected error during geo check", e, context = "GeoCheck")
                null // Return null for any other errors too
            }
        }
    }

    private suspend fun getLspPeers(): List<LnPeer> {
        val blocktankPeers = Env.trustedLnPeers
        // TODO get from blocktank info when lightningService.setup sets trustedPeers0conf using BT API
        // pseudocode idea:
        // val blocktankPeers = getInfo(refresh = true)?.nodes?.map { LnPeer(nodeId = it.pubkey, address = "TO_DO") }
        // .orEmpty()
        return blocktankPeers
    }

    suspend fun getConnectedPeers(): List<LnPeer> = lightningService.peers.orEmpty()

    suspend fun hasExternalNode() = getConnectedPeers().any { connectedPeer -> connectedPeer !in getLspPeers() }

    suspend fun shouldBlockLightning(): Boolean {
        if (hasExternalNode()) return false

        return runCatching { isGeoBlocked() ?: false }
            .onFailure {
                Logger.error("Error in shouldBlockLightning, defaulting to not blocked", context = "GeoCheck")
            }
            .getOrDefault(false)
    }
}

// endregion

// region Activity
private const val CHUNCK_SIZE = 50

class ActivityService(
    private val coreService: CoreService,
    private val cacheStore: CacheStore,
) {
    suspend fun removeAll() {
        ServiceQueue.CORE.background {
            // Get all activities and delete them one by one
            val activities = getActivities(
                filter = ActivityFilter.ALL,
                txType = null,
                tags = null,
                search = null,
                minDate = null,
                maxDate = null,
                limit = null,
                sortDirection = null
            )
            for (activity in activities) {
                val id = when (activity) {
                    is Activity.Lightning -> activity.v1.id
                    is Activity.Onchain -> activity.v1.id
                }
                deleteActivityById(activityId = id)
            }
        }
    }

    suspend fun insert(activity: Activity) {
        ServiceQueue.CORE.background {
            insertActivity(activity)
        }
    }

    suspend fun getActivity(id: String): Activity? {
        return ServiceQueue.CORE.background {
            getActivityById(id)
        }
    }

    suspend fun get(
        filter: ActivityFilter? = null,
        txType: PaymentType? = null,
        tags: List<String>? = null,
        search: String? = null,
        minDate: ULong? = null,
        maxDate: ULong? = null,
        limit: UInt? = null,
        sortDirection: SortDirection? = null,
    ): List<Activity> {
        return ServiceQueue.CORE.background {
            getActivities(filter, txType, tags, search, minDate, maxDate, limit, sortDirection)
        }
    }

    suspend fun update(id: String, activity: Activity) {
        ServiceQueue.CORE.background {
            updateActivity(id, activity)
        }
    }

    suspend fun delete(id: String): Boolean {
        return ServiceQueue.CORE.background {
            deleteActivityById(id)
        }
    }

    suspend fun appendTags(toActivityId: String, tags: List<String>): Result<Unit> {
        return try {
            ServiceQueue.CORE.background {
                addTags(toActivityId, tags)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun dropTags(fromActivityId: String, tags: List<String>) {
        ServiceQueue.CORE.background {
            removeTags(fromActivityId, tags)
        }
    }

    suspend fun tags(forActivityId: String): List<String> {
        return ServiceQueue.CORE.background {
            getTags(forActivityId)
        }
    }

    suspend fun allPossibleTags(): List<String> {
        return ServiceQueue.CORE.background {
            getAllUniqueTags()
        }
    }

    suspend fun syncLdkNodePayments(payments: List<PaymentDetails>, forceUpdate: Boolean = false) {
        ServiceQueue.CORE.background {
            withContext(Dispatchers.IO) {
                val allResults = mutableListOf<Result<String>>()

                payments.chunked(CHUNCK_SIZE).forEach { chunk ->
                    val results = chunk.map { payment ->
                        async {
                            runCatching {
                                processSinglePayment(payment, forceUpdate)
                                payment.id
                            }.onFailure { e ->
                                Logger.error("Error syncing payment ${payment.id}:", e, context = "CoreService")
                            }
                        }
                    }.awaitAll()

                    allResults.addAll(results)
                }

                val (successful, failed) = allResults.partition { it.isSuccess }

                Logger.info(
                    "Synced ${successful.size} payments successfully, ${failed.size} failed",
                    context = "CoreService"
                )
            }
        }
    }

    private suspend fun processSinglePayment(payment: PaymentDetails, forceUpdate: Boolean) {
        val state = when (payment.status) {
            PaymentStatus.FAILED -> PaymentState.FAILED
            PaymentStatus.PENDING -> PaymentState.PENDING
            PaymentStatus.SUCCEEDED -> PaymentState.SUCCEEDED
        }

        when (val kind = payment.kind) {
            is PaymentKind.Onchain -> {
                processOnchainPayment(kind = kind, payment = payment, forceUpdate = forceUpdate)
            }

            is PaymentKind.Bolt11 -> {
                processBolt11(kind = kind, payment = payment, state = state)
            }

            else -> Unit // Handle spontaneous payments if needed
        }
    }

    private suspend fun processBolt11(
        kind: PaymentKind.Bolt11,
        payment: PaymentDetails,
        state: PaymentState,
    ) {
        // Skip pending inbound payments, just means an invoice was created
        if (
            payment.status == PaymentStatus.PENDING &&
            payment.direction == PaymentDirection.INBOUND
        ) {
            return
        }

        val existingActivity = getActivityById(payment.id)
        if (
            existingActivity as? Activity.Lightning != null &&
            (existingActivity.v1.updatedAt ?: 0u) > payment.latestUpdateTimestamp
        ) {
            return
        }

        val ln = if (existingActivity is Activity.Lightning) {
            existingActivity.v1.copy(
                updatedAt = payment.latestUpdateTimestamp,
                status = state
            )
        } else {
            LightningActivity(
                id = payment.id,
                txType = payment.direction.toPaymentType(),
                status = state,
                value = payment.amountSats ?: 0u,
                fee = (payment.feePaidMsat ?: 0u) / 1000u,
                invoice = kind.bolt11 ?: "Loading...",
                message = kind.description.orEmpty(),
                timestamp = payment.latestUpdateTimestamp,
                preimage = kind.preimage,
                createdAt = payment.latestUpdateTimestamp,
                updatedAt = payment.latestUpdateTimestamp,
            )
        }

        if (getActivityById(payment.id) != null) {
            updateActivity(payment.id, Activity.Lightning(ln))
        } else {
            upsertActivity(Activity.Lightning(ln))
        }
    }

    private suspend fun processOnchainPayment(
        kind: PaymentKind.Onchain,
        payment: PaymentDetails,
        forceUpdate: Boolean,
    ) {
        var isConfirmed = false
        var confirmedTimestamp: ULong? = null

        val status = kind.status
        if (status is ConfirmationStatus.Confirmed) {
            isConfirmed = true
            confirmedTimestamp = status.timestamp
        }

        // Ensure confirmTimestamp is at least equal to timestamp when confirmed
        val timestamp = payment.latestUpdateTimestamp

        if (isConfirmed && confirmedTimestamp != null && confirmedTimestamp < timestamp) {
            confirmedTimestamp = timestamp
        }

        val existingActivity = getActivityById(payment.id)
        if (existingActivity != null &&
            existingActivity is Activity.Onchain &&
            (existingActivity.v1.updatedAt ?: 0u) > payment.latestUpdateTimestamp
        ) {
            return
        }

        val onChain = if (existingActivity is Activity.Onchain) {
            existingActivity.v1.copy(
                confirmed = isConfirmed,
                confirmTimestamp = confirmedTimestamp,
                updatedAt = timestamp,
            )
        } else {
            OnchainActivity(
                id = payment.id,
                txType = payment.direction.toPaymentType(),
                txId = kind.txid,
                value = payment.amountSats ?: 0u,
                fee = (payment.feePaidMsat ?: 0u) / 1000u,
                feeRate = 1u,
                address = "Loading...",
                confirmed = isConfirmed,
                timestamp = timestamp,
                isBoosted = false,
                isTransfer = false,
                doesExist = true,
                confirmTimestamp = confirmedTimestamp,
                channelId = null,
                transferTxId = null,
                createdAt = timestamp,
                updatedAt = timestamp,
            )
        }

        if (onChain.id in cacheStore.data.first().deletedActivities && !forceUpdate) {
            Logger.debug("Activity ${onChain.id} was already deleted, skipping", context = TAG)
            return
        }

        if (existingActivity != null) {
            updateActivity(payment.id, Activity.Onchain(onChain))
        } else {
            upsertActivity(Activity.Onchain(onChain))
        }
    }

    private fun PaymentDirection.toPaymentType(): PaymentType =
        if (this == PaymentDirection.OUTBOUND) PaymentType.SENT else PaymentType.RECEIVED

    // MARK: - Test Data Generation (regtest only)

    suspend fun generateRandomTestData(count: Int = 100) {
        if (Env.network != Network.REGTEST) {
            throw AppError(message = "Regtest only")
        }
        ServiceQueue.CORE.background {
            val timestamp = System.currentTimeMillis().toULong() / 1000u
            val possibleTags =
                listOf("coffee", "food", "shopping", "transport", "entertainment", "work", "friends", "family")
            val possibleMessages = listOf(
                "Coffee at Starbucks",
                "Lunch with friends",
                "Uber ride",
                "Movie tickets",
                "Groceries",
                "Work payment",
                "Gift for mom",
                "Split dinner bill",
                "Monthly rent",
                "Gym membership",
                "Very long invoice message to test truncation in list",
            )

            repeat(count) { i ->
                val isLightning = Random.Default.nextBoolean()
                val value = (1000..1_000_000).random().toULong()
                val txTimestamp =
                    (timestamp.toLong() - (0..30L * 24 * 60 * 60).random()).toULong() // Random time in last 30 days
                val txType = if (Random.Default.nextBoolean()) PaymentType.SENT else PaymentType.RECEIVED
                val status = when ((0..10).random()) {
                    in 0..7 -> PaymentState.SUCCEEDED // 80% chance
                    8 -> PaymentState.PENDING // 10% chance
                    else -> PaymentState.FAILED // 10% chance
                }

                val activity: Activity
                val id: String

                if (isLightning) {
                    id = "test-lightning-$i"
                    activity = Activity.Lightning(
                        LightningActivity(
                            id = id,
                            txType = txType,
                            status = status,
                            value = value,
                            fee = (1..1_000).random().toULong(),
                            invoice = "lnbc$value",
                            message = possibleMessages.random(),
                            timestamp = txTimestamp,
                            preimage = if (Random.Default.nextBoolean()) "preimage$i" else null,
                            createdAt = txTimestamp,
                            updatedAt = txTimestamp
                        )
                    )
                } else {
                    id = "test-onchain-$i"
                    activity = Activity.Onchain(
                        OnchainActivity(
                            id = id,
                            txType = txType,
                            txId = "a".repeat(64), // Mock txid
                            value = value,
                            fee = (100..10_000).random().toULong(),
                            feeRate = (1..100).random().toULong(),
                            address = "bc1...$i",
                            confirmed = Random.Default.nextBoolean(),
                            timestamp = txTimestamp,
                            isBoosted = Random.Default.nextBoolean(),
                            isTransfer = Random.Default.nextBoolean(),
                            doesExist = true,
                            confirmTimestamp = if (Random.Default.nextBoolean()) txTimestamp + 3600.toULong() else null,
                            channelId = if (Random.Default.nextBoolean()) "channel$i" else null,
                            transferTxId = null,
                            createdAt = txTimestamp,
                            updatedAt = txTimestamp
                        )
                    )
                }

                // Insert activity
                insertActivity(activity)

                // Add random tags
                val numTags = (0..3).random()
                if (numTags > 0) {
                    val tags = (0 until numTags).map { possibleTags.random() }
                    appendTags(id, tags)
                }
            }
        }
    }

    companion object {
        private const val TAG = "ActivityService"
    }
}

// endregion

// region Blocktank

class BlocktankService(
    private val coreService: CoreService,
    private val lightningService: LightningService,
) {

    suspend fun info(refresh: Boolean = true): IBtInfo? {
        return try {
            ServiceQueue.CORE.background {
                getInfo(refresh = refresh)
            }
        } catch (e: Exception) {
            handleNetworkError("info", e)
        }
    }

    private suspend fun fees(refresh: Boolean = true): FeeRates? {
        return try {
            info(refresh)?.onchain?.feeRates
        } catch (e: Exception) {
            Logger.warn("Failed to get fees: ${e.message}")
            null
        }
    }

    suspend fun getFees(): Result<FeeRates> {
        return try {
            var fees = fees(refresh = true)
            if (fees == null) {
                Logger.warn("Failed to fetch fresh fee rate, using cached rate.")
                fees = fees(refresh = false)
            }
            if (fees == null) {
                return Result.failure(AppError("Fees unavailable from bitkit-core"))
            }

            Result.success(fees)
        } catch (e: Exception) {
            Logger.error("Error getting fees", e)
            Result.failure(e)
        }
    }

    suspend fun createCjit(
        channelSizeSat: ULong,
        invoiceSat: ULong,
        invoiceDescription: String,
        nodeId: String,
        channelExpiryWeeks: UInt,
        options: CreateCjitOptions,
    ): IcJitEntry {
        return ServiceQueue.CORE.background {
            createCjitEntry(
                channelSizeSat = channelSizeSat,
                invoiceSat = invoiceSat,
                invoiceDescription = invoiceDescription,
                nodeId = nodeId,
                channelExpiryWeeks = channelExpiryWeeks,
                options = options
            )
        }
    }

    suspend fun cjitOrders(
        entryIds: List<String>? = null,
        filter: CJitStateEnum? = null,
        refresh: Boolean = true,
    ): List<IcJitEntry> {
        return try {
            ServiceQueue.CORE.background {
                if (refresh) {
                    try {
                        refreshActiveCjitEntries()
                    } catch (e: Exception) {
                        Logger.warn("Failed to refresh CJIT entries: ${e.message}")
                        // Continue with cached data
                    }
                }
                getCjitEntries(
                    entryIds = entryIds,
                    filter = filter,
                    refresh = false
                ) // Use cached after refresh attempt
            }
        } catch (e: Exception) {
            Logger.error("Error getting CJIT orders", e)
            emptyList() // Return empty list on error
        }
    }

    suspend fun newOrder(
        lspBalanceSat: ULong,
        channelExpiryWeeks: UInt,
        options: CreateOrderOptions,
    ): IBtOrder {
        return ServiceQueue.CORE.background {
            createOrder(lspBalanceSat = lspBalanceSat, channelExpiryWeeks = channelExpiryWeeks, options = options)
        }
    }

    suspend fun estimateFee(
        lspBalanceSat: ULong,
        channelExpiryWeeks: UInt,
        options: CreateOrderOptions? = null,
    ): IBtEstimateFeeResponse2 {
        return ServiceQueue.CORE.background {
            estimateOrderFeeFull(
                lspBalanceSat = lspBalanceSat,
                channelExpiryWeeks = channelExpiryWeeks,
                options = options,
            )
        }
    }

    suspend fun orders(
        orderIds: List<String>? = null,
        filter: BtOrderState2? = null,
        refresh: Boolean = true,
    ): List<IBtOrder> {
        return try {
            ServiceQueue.CORE.background {
                if (refresh) {
                    try {
                        refreshActiveOrders()
                    } catch (e: Exception) {
                        Logger.warn("Failed to refresh orders: ${e.message}")
                        // Continue with cached data
                    }
                }
                getOrders(orderIds = orderIds, filter = filter, refresh = false) // Use cached after refresh attempt
            }
        } catch (e: Exception) {
            Logger.error("Error getting orders", e)
            emptyList() // Return empty list on error
        }
    }

    suspend fun open(orderId: String): IBtOrder {
        val nodeId = lightningService.nodeId ?: throw ServiceError.NodeNotStarted

        val latestOrder = ServiceQueue.CORE.background {
            getOrders(orderIds = listOf(orderId), filter = null, refresh = true).firstOrNull()
        }

        if (latestOrder?.state2 != BtOrderState2.PAID) {
            throw AppError(
                message = "Order not paid, Order state: ${latestOrder?.state2}"
            )
        }

        return ServiceQueue.CORE.background {
            openChannel(orderId = orderId, connectionString = nodeId)
        }
    }

    /**
     * Handles network errors gracefully, returning null and logging appropriately
     */
    private fun <T> handleNetworkError(operation: String, e: Exception): T? {
        val isNetworkError = e is UnknownHostException ||
            e is SocketTimeoutException ||
            e is ConnectException ||
            e.message?.contains("Unable to resolve host") == true ||
            e.message?.contains("Network") == true ||
            e.message?.contains("api1.blocktank.to") == true

        if (isNetworkError) {
            Logger.warn("Network error in $operation: ${e.message}")
        } else {
            Logger.error("Error in $operation", e)
        }

        return null
    }

    // MARK: - Regtest methods (these don't typically require network so keep as-is)
    suspend fun regtestMine(count: UInt = 1u) {
        com.synonym.bitkitcore.regtestMine(count = count)
    }

    suspend fun regtestDeposit(address: String, amountSat: ULong = 10_000_000uL): String {
        return com.synonym.bitkitcore.regtestDeposit(
            address = address,
            amountSat = amountSat,
        )
    }

    suspend fun regtestPay(invoice: String, amountSat: ULong? = null): String {
        return com.synonym.bitkitcore.regtestPay(
            invoice = invoice,
            amountSat = amountSat,
        )
    }

    suspend fun regtestCloseChannel(fundingTxId: String, vout: UInt, forceCloseAfterS: ULong = 86_400uL): String {
        return com.synonym.bitkitcore.regtestCloseChannel(
            fundingTxId = fundingTxId,
            vout = vout,
            forceCloseAfterS = forceCloseAfterS,
        )
    }
}

// endregion

// region Onchain

class OnchainService {
    suspend fun generateMnemonic(wordCount: WordCount = WordCount.WORDS12): String {
        return ServiceQueue.CORE.background {
            com.synonym.bitkitcore.generateMnemonic(wordCount = wordCount)
        }
    }

    suspend fun deriveBitcoinAddress(
        mnemonicPhrase: String,
        derivationPathStr: String?,
        network: Network?,
        bip39Passphrase: String?,
    ): GetAddressResponse {
        return ServiceQueue.CORE.background {
            com.synonym.bitkitcore.deriveBitcoinAddress(
                mnemonicPhrase = mnemonicPhrase,
                derivationPathStr = derivationPathStr,
                network = network?.toCoreNetwork(),
                bip39Passphrase = bip39Passphrase,
            )
        }
    }

    suspend fun deriveBitcoinAddresses(
        mnemonicPhrase: String,
        derivationPathStr: String?,
        network: Network?,
        bip39Passphrase: String?,
        isChange: Boolean?,
        startIndex: UInt?,
        count: UInt?,
    ): GetAddressesResponse {
        return ServiceQueue.CORE.background {
            return@background com.synonym.bitkitcore.deriveBitcoinAddresses(
                mnemonicPhrase = mnemonicPhrase,
                derivationPathStr = derivationPathStr,
                network = network?.toCoreNetwork(),
                bip39Passphrase = bip39Passphrase,
                isChange = isChange,
                startIndex = startIndex,
                count = count,
            )
        }
    }

    suspend fun derivePrivateKey(
        mnemonicPhrase: String,
        derivationPathStr: String?,
        network: Network?,
        bip39Passphrase: String?,
    ): String {
        return ServiceQueue.CORE.background {
            com.synonym.bitkitcore.derivePrivateKey(
                mnemonicPhrase = mnemonicPhrase,
                derivationPathStr = derivationPathStr,
                network = network?.toCoreNetwork(),
                bip39Passphrase = bip39Passphrase,
            )
        }
    }
}

// endregion
