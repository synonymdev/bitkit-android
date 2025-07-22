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
import kotlinx.coroutines.flow.first
import org.lightningdevkit.ldknode.ConfirmationStatus
import org.lightningdevkit.ldknode.Network
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
import to.bitkit.async.ServiceQueue
import to.bitkit.data.CacheStore
import to.bitkit.data.SettingsStore
import to.bitkit.env.Env
import to.bitkit.ext.amountSats
import to.bitkit.models.LnPeer
import to.bitkit.models.toCoreNetwork
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

// region Core

@Singleton
class CoreService @Inject constructor(
    private val lightningService: LightningService,
    private val httpClient: HttpClient,
    private val settingsStore: SettingsStore,
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
                val blocktankUrl = Env.blocktankClientServer
                updateBlocktankUrl(newUrl = blocktankUrl)
                Logger.info("Blocktank URL updated to: $blocktankUrl")
            } catch (e: Exception) {
                Logger.error("Failed to update Blocktank URL", e)
            }
        }
    }

    /** Returns true if geo blocked */
    suspend fun checkGeoStatus(): Boolean? {
        return ServiceQueue.CORE.background {
            Logger.info("Checking geo status…", context = "GeoCheck")
            val response = httpClient.get(Env.geoCheckUrl)
            Logger.debug("Received geo status response: ${response.status.value}", context = "GeoCheck")

            when (response.status.value) {
                HttpStatusCode.OK.value -> {
                    Logger.info("Region allowed", context = "GeoCheck")
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
    }

    private suspend fun getLspPeers(): List<LnPeer> {
        val blocktankPeers = Env.trustedLnPeers
        // TODO get from blocktank info when lightningService.setup sets trustedPeers0conf using BT API
        // pseudocode idea:
        // val blocktankPeers = getInfo(refresh = true)?.nodes?.map { LnPeer(nodeId = it.pubkey, address = "TO_DO") }.orEmpty()
        return blocktankPeers
    }

    suspend fun getConnectedPeers(): List<LnPeer> = lightningService.peers.orEmpty()

    suspend fun hasExternalNode() = getConnectedPeers().any { connectedPeer -> connectedPeer !in getLspPeers() }

    // TODO this is business logic, should be moved to the domain layer in the future
    // TODO this spams network calls too often, it needs a caching mechanism
    suspend fun shouldBlockLightning() = checkGeoStatus() == true && !hasExternalNode()
}

// endregion

// region Activity

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
            for (payment in payments) {
                try {
                    val state = when (payment.status) {
                        PaymentStatus.FAILED -> PaymentState.FAILED
                        PaymentStatus.PENDING -> PaymentState.PENDING
                        PaymentStatus.SUCCEEDED -> PaymentState.SUCCEEDED
                    }

                    when (val kind = payment.kind) {
                        is PaymentKind.Onchain -> {
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
                            if (existingActivity != null && existingActivity is Activity.Onchain && (existingActivity.v1.updatedAt
                                    ?: 0u) > payment.latestUpdateTimestamp
                            ) {
                                continue
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
                                    feeRate = 1u, // TODO: get from somewhere
                                    address = "todo_find_address", // TODO: find address
                                    confirmed = isConfirmed,
                                    timestamp = timestamp,
                                    isBoosted = false,
                                    isTransfer = false, // TODO: handle when paying for order
                                    doesExist = true,
                                    confirmTimestamp = confirmedTimestamp,
                                    channelId = null, // TODO: get from linked order
                                    transferTxId = null, // TODO: get from linked order
                                    createdAt = timestamp,
                                    updatedAt = timestamp,
                                )
                            }

                            if (onChain.id in cacheStore.data.first().deletedActivities && !forceUpdate) {
                                Logger.debug("Activity ${onChain.id} was already deleted, skipping", context = TAG)
                                continue
                            }

                            if (existingActivity != null) {
                                updateActivity(payment.id, Activity.Onchain(onChain))
                            } else {
                                upsertActivity(Activity.Onchain(onChain))
                            }
                        }

                        is PaymentKind.Bolt11 -> {
                            // Skip pending inbound payments, just means they created an invoice
                            if (payment.status == PaymentStatus.PENDING && payment.direction == PaymentDirection.INBOUND) {
                                continue
                            }

                            val ln = LightningActivity(
                                id = payment.id,
                                txType = payment.direction.toPaymentType(),
                                status = state,
                                value = payment.amountSats ?: 0u,
                                fee = (payment.feePaidMsat ?: 0u) / 1000u,
                                invoice = "lnbc123_todo", // TODO
                                message = kind.description.orEmpty(),
                                timestamp = payment.latestUpdateTimestamp,
                                preimage = kind.preimage,
                                createdAt = payment.latestUpdateTimestamp,
                                updatedAt = payment.latestUpdateTimestamp,
                            )

                            if (getActivityById(payment.id) != null) {
                                updateActivity(payment.id, Activity.Lightning(ln))
                            } else {
                                upsertActivity(Activity.Lightning(ln))
                            }
                        }

                        else -> Unit // Handle spontaneous payments if needed
                    }
                } catch (e: Throwable) {
                    Logger.error("Error syncing LDK payment:", e, context = "CoreService")
                    throw e
                }
            }
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
        return ServiceQueue.CORE.background {
            getInfo(refresh = refresh)
        }
    }

    private suspend fun fees(refresh: Boolean = true): FeeRates? {
        return info(refresh)?.onchain?.feeRates
    }

    suspend fun getFees(): Result<FeeRates> {
        var fees = fees(refresh = true)
        if (fees == null) {
            Logger.warn("Failed to fetch fresh fee rate, using cached rate.")
            fees = fees(refresh = false)
        }
        if (fees == null) {
            return Result.failure(AppError("Fees unavailable from bitkit-core"))
        }

        return Result.success(fees)
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
        return ServiceQueue.CORE.background {
            if (refresh) {
                refreshActiveCjitEntries()
            }
            getCjitEntries(entryIds = entryIds, filter = filter, refresh = refresh)
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
        return ServiceQueue.CORE.background {
            if (refresh) {
                refreshActiveOrders()
            }
            getOrders(orderIds = orderIds, filter = filter, refresh = refresh)
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

    // MARK: - Regtest methods
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
