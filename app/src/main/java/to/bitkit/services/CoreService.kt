package to.bitkit.services

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import org.lightningdevkit.ldknode.Network
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentStatus
import to.bitkit.async.ServiceQueue
import to.bitkit.env.Env
import to.bitkit.ext.amountSats
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import to.bitkit.utils.ServiceError
import uniffi.bitkitcore.Activity
import uniffi.bitkitcore.ActivityFilter
import uniffi.bitkitcore.BtOrderState2
import uniffi.bitkitcore.CJitStateEnum
import uniffi.bitkitcore.CreateCjitOptions
import uniffi.bitkitcore.CreateOrderOptions
import uniffi.bitkitcore.IBtEstimateFeeResponse2
import uniffi.bitkitcore.IBtInfo
import uniffi.bitkitcore.IBtOrder
import uniffi.bitkitcore.IcJitEntry
import uniffi.bitkitcore.LightningActivity
import uniffi.bitkitcore.OnchainActivity
import uniffi.bitkitcore.PaymentState
import uniffi.bitkitcore.PaymentType
import uniffi.bitkitcore.SortDirection
import uniffi.bitkitcore.addTags
import uniffi.bitkitcore.createCjitEntry
import uniffi.bitkitcore.createOrder
import uniffi.bitkitcore.deleteActivityById
import uniffi.bitkitcore.estimateOrderFeeFull
import uniffi.bitkitcore.getActivities
import uniffi.bitkitcore.getActivityById
import uniffi.bitkitcore.getAllUniqueTags
import uniffi.bitkitcore.getCjitEntries
import uniffi.bitkitcore.getInfo
import uniffi.bitkitcore.getOrders
import uniffi.bitkitcore.getTags
import uniffi.bitkitcore.initDb
import uniffi.bitkitcore.insertActivity
import uniffi.bitkitcore.openChannel
import uniffi.bitkitcore.removeTags
import uniffi.bitkitcore.updateActivity
import uniffi.bitkitcore.updateBlocktankUrl
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

// region Core

@Singleton
class CoreService @Inject constructor(
    private val lightningService: LightningService,
    private val httpClient: HttpClient,
) {
    private var walletIndex: Int = 0

    val activity: ActivityService by lazy { ActivityService(coreService = this) }
    val blocktank: BlocktankService by lazy {
        BlocktankService(
            coreService = this,
            lightningService = lightningService,
        )
    }

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
                Logger.info("Blocktank URL updated to $blocktankUrl")
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
}

// endregion

// region Activity

class ActivityService(
    private val coreService: CoreService,
) {
    suspend fun removeAll() {
        ServiceQueue.CORE.background { // Only allow removing on regtest for now
            if (Env.network != Network.REGTEST) {
                throw AppError(message = "Regtest only")
            }

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

    suspend fun syncLdkNodePayments(payments: List<PaymentDetails>) {
        ServiceQueue.CORE.background {
            var addedCount = 0
            var updatedCount = 0

            for (payment in payments) { // Skip pending inbound payments, just means they created an invoice
                if (payment.status == PaymentStatus.PENDING && payment.direction == PaymentDirection.INBOUND) {
                    continue
                }

                val state = when (payment.status) {
                    PaymentStatus.FAILED -> PaymentState.FAILED
                    PaymentStatus.PENDING -> PaymentState.PENDING
                    PaymentStatus.SUCCEEDED -> PaymentState.SUCCEEDED
                }

                val ln = LightningActivity(
                    id = payment.id,
                    txType = if (payment.direction == PaymentDirection.OUTBOUND) PaymentType.SENT else PaymentType.RECEIVED,
                    status = state,
                    value = payment.amountSats ?: 0u,
                    fee = null, // TODO
                    invoice = "lnbc123", // TODO
                    message = "",
                    timestamp = payment.latestUpdateTimestamp,
                    preimage = null,
                    createdAt = payment.latestUpdateTimestamp,
                    updatedAt = payment.latestUpdateTimestamp,
                )

                if (getActivityById(payment.id) != null) {
                    updateActivity(payment.id, Activity.Lightning(ln))
                    updatedCount++
                } else {
                    insertActivity(Activity.Lightning(ln))
                    addedCount++
                }

                // TODO: handle onchain activity when it comes in ldk-node
            }

            Logger.info("Synced LDK payments - Added: $addedCount, Updated: $updatedCount")
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

    // MARK: - Tag Methods

    suspend fun appendTags(toActivityId: String, tags: List<String>) : Result<Unit>{
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
                "Gym membership"
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
}

// endregion

// region Blocktank

class BlocktankService(
    private val coreService: CoreService,
    private val lightningService: LightningService,
) {
    suspend fun info(refresh: Boolean = false): IBtInfo? {
        return ServiceQueue.CORE.background {
            getInfo(refresh = refresh)
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
        return ServiceQueue.CORE.background {
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

    suspend fun newOrderFeeEstimate(
        lspBalanceSat: ULong,
        channelExpiryWeeks: UInt,
        options: CreateOrderOptions,
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
}

// endregion
