package to.bitkit.services

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.ActivityFilter
import com.synonym.bitkitcore.ActivityTags
import com.synonym.bitkitcore.BtOrderState2
import com.synonym.bitkitcore.CJitStateEnum
import com.synonym.bitkitcore.ClosedChannelDetails
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
import com.synonym.bitkitcore.PreActivityMetadata
import com.synonym.bitkitcore.SortDirection
import com.synonym.bitkitcore.WordCount
import com.synonym.bitkitcore.addTags
import com.synonym.bitkitcore.createCjitEntry
import com.synonym.bitkitcore.createOrder
import com.synonym.bitkitcore.deleteActivityById
import com.synonym.bitkitcore.estimateOrderFeeFull
import com.synonym.bitkitcore.getActivities
import com.synonym.bitkitcore.getActivityById
import com.synonym.bitkitcore.getActivityByTxId
import com.synonym.bitkitcore.getAllClosedChannels
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
import com.synonym.bitkitcore.upsertActivities
import com.synonym.bitkitcore.upsertActivity
import com.synonym.bitkitcore.upsertCjitEntries
import com.synonym.bitkitcore.upsertClosedChannels
import com.synonym.bitkitcore.upsertInfo
import com.synonym.bitkitcore.upsertOrders
import com.synonym.bitkitcore.wipeAllDatabases
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.ConfirmationStatus
import org.lightningdevkit.ldknode.Network
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
import org.lightningdevkit.ldknode.TransactionDetails
import to.bitkit.async.ServiceQueue
import to.bitkit.data.CacheStore
import to.bitkit.env.Env
import to.bitkit.ext.amountSats
import to.bitkit.ext.create
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
    private val cacheStore: CacheStore,
) {
    private var walletIndex: Int = 0

    val activity: ActivityService by lazy {
        ActivityService(
            coreService = this,
            cacheStore = cacheStore,
            lightningService = lightningService
        )
    }
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

    @Suppress("KotlinConstantConditions")
    private suspend fun isGeoBlocked(): Boolean {
        if (!Env.isGeoblockingEnabled) {
            Logger.verbose("Geoblocking disabled via build config", context = "GeoCheck")
            return false
        }

        return ServiceQueue.CORE.background {
            runCatching {
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
                        Logger.warn(
                            "Unexpected status code: ${response.status.value}, defaulting to false",
                            context = "GeoCheck"
                        )
                        false
                    }
                }
            }.onFailure {
                Logger.warn("Error. defaulting isGeoBlocked to false", context = "GeoCheck")
            }.getOrDefault(false)
        }
    }

    /**
     * This method checks if the device is in a is geo blocked region and if lightning features should be blocked
     * @return pair of `isGeoBlocked` to `shouldBlockLightningReceive`
     * */
    suspend fun checkGeoBlock(): Pair<Boolean, Boolean> {
        val geoBlocked = isGeoBlocked()
        val shouldBlockLightningReceive = when {
            lightningService.hasExternalPeers() -> !lightningService.canReceive()
            else -> geoBlocked
        }

        return Pair(geoBlocked, shouldBlockLightningReceive)
    }

    suspend fun wipeData(): Result<Unit> = ServiceQueue.CORE.background {
        runCatching {
            val result = wipeAllDatabases()
            Logger.info("Core DB wipe: $result", context = TAG)
        }.onFailure { e ->
            Logger.error("Core DB wipe error", e, context = TAG)
        }
    }

    suspend fun isAddressUsed(address: String): Boolean {
        return ServiceQueue.CORE.background {
            com.synonym.bitkitcore.isAddressUsed(address = address)
        }
    }

    companion object {
        private const val TAG = "CoreService"
    }
}

// endregion

// region Activity
private const val CHUNK_SIZE = 50

@Suppress("LargeClass")
class ActivityService(
    @Suppress("unused") private val coreService: CoreService, // used to ensure CoreService inits first
    private val cacheStore: CacheStore,
    private val lightningService: LightningService,
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

    suspend fun upsert(activity: Activity) = ServiceQueue.CORE.background {
        upsertActivity(activity)
    }

    suspend fun upsertList(activities: List<Activity>) = ServiceQueue.CORE.background {
        upsertActivities(activities)
    }

    suspend fun getActivity(id: String): Activity? {
        return ServiceQueue.CORE.background {
            getActivityById(id)
        }
    }

    suspend fun getOnchainActivityByTxId(txId: String): OnchainActivity? = ServiceQueue.CORE.background {
        getActivityByTxId(txId = txId)
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

    suspend fun upsertTags(activityTags: List<ActivityTags>) = ServiceQueue.CORE.background {
        com.synonym.bitkitcore.upsertTags(activityTags)
    }

    suspend fun getAllActivitiesTags(): List<ActivityTags> = ServiceQueue.CORE.background {
        com.synonym.bitkitcore.getAllActivitiesTags()
    }

    suspend fun getAllPreActivityMetadata(): List<PreActivityMetadata> = ServiceQueue.CORE.background {
        com.synonym.bitkitcore.getAllPreActivityMetadata()
    }

    suspend fun upsertPreActivityMetadata(list: List<PreActivityMetadata>) = ServiceQueue.CORE.background {
        com.synonym.bitkitcore.upsertPreActivityMetadata(list)
    }

    suspend fun addPreActivityMetadata(preActivityMetadata: PreActivityMetadata) = ServiceQueue.CORE.background {
        com.synonym.bitkitcore.addPreActivityMetadata(preActivityMetadata = preActivityMetadata)
    }

    suspend fun addPreActivityMetadataTags(paymentId: String, tags: List<String>) = ServiceQueue.CORE.background {
        com.synonym.bitkitcore.addPreActivityMetadataTags(paymentId = paymentId, tags = tags)
    }

    suspend fun removePreActivityMetadataTags(paymentId: String, tags: List<String>) = ServiceQueue.CORE.background {
        com.synonym.bitkitcore.removePreActivityMetadataTags(paymentId = paymentId, tags = tags)
    }

    suspend fun resetPreActivityMetadataTags(paymentId: String) = ServiceQueue.CORE.background {
        com.synonym.bitkitcore.resetPreActivityMetadataTags(paymentId = paymentId)
    }

    suspend fun getPreActivityMetadata(
        searchKey: String,
        searchByAddress: Boolean = false,
    ): PreActivityMetadata? = ServiceQueue.CORE.background {
        com.synonym.bitkitcore.getPreActivityMetadata(searchKey = searchKey, searchByAddress = searchByAddress)
    }

    suspend fun deletePreActivityMetadata(paymentId: String) = ServiceQueue.CORE.background {
        com.synonym.bitkitcore.deletePreActivityMetadata(paymentId = paymentId)
    }

    suspend fun upsertClosedChannelList(closedChannels: List<ClosedChannelDetails>) = ServiceQueue.CORE.background {
        upsertClosedChannels(closedChannels)
    }

    suspend fun closedChannels(
        sortDirection: SortDirection,
    ): List<ClosedChannelDetails> = ServiceQueue.CORE.background {
        getAllClosedChannels(sortDirection)
    }

    suspend fun handlePaymentEvent(paymentHash: String) {
        ServiceQueue.CORE.background {
            val payments = lightningService.payments ?: run {
                Logger.warn("No payments available for hash $paymentHash", context = TAG)
                return@background
            }

            val payment = payments.firstOrNull { it.id == paymentHash }
            if (payment != null) {
                // Lightning payments don't need channel IDs, only onchain payments do
                val channelIdsByTxId = emptyMap<String, String>()
                processSinglePayment(payment, forceUpdate = false, channelIdsByTxId = channelIdsByTxId)
            } else {
                Logger.info("Payment not found for hash $paymentHash - syncing all payments", context = TAG)
                // For full sync, we need channel IDs for onchain payments
                // This will be handled by ActivityRepo.syncLdkNodePayments which calls findChannelsForPayments
                syncLdkNodePaymentsToActivities(payments, channelIdsByTxId = emptyMap())
            }
        }
    }

    suspend fun syncLdkNodePaymentsToActivities(
        payments: List<PaymentDetails>,
        forceUpdate: Boolean = false,
        channelIdsByTxId: Map<String, String> = emptyMap(),
    ) {
        ServiceQueue.CORE.background {
            val allResults = mutableListOf<Result<String>>()

            payments.chunked(CHUNK_SIZE).forEach { chunk ->
                val results = chunk.map { payment ->
                    async {
                        runCatching {
                            processSinglePayment(payment, forceUpdate, channelIdsByTxId)
                            payment.id
                        }.onFailure { e ->
                            Logger.error("Error syncing payment with id: ${payment.id}:", e, context = TAG)
                        }
                    }
                }.awaitAll()

                allResults.addAll(results)
            }

            val (successful, failed) = allResults.partition { it.isSuccess }

            Logger.info("Synced ${successful.size} payments successfully, ${failed.size} failed", context = TAG)
        }
    }

    private suspend fun processSinglePayment(
        payment: PaymentDetails,
        forceUpdate: Boolean,
        channelIdsByTxId: Map<String, String>,
    ) {
        val state = when (payment.status) {
            PaymentStatus.FAILED -> PaymentState.FAILED
            PaymentStatus.PENDING -> PaymentState.PENDING
            PaymentStatus.SUCCEEDED -> PaymentState.SUCCEEDED
        }

        when (val kind = payment.kind) {
            is PaymentKind.Onchain -> {
                val channelId = channelIdsByTxId[kind.txid]
                processOnchainPayment(
                    kind = kind,
                    payment = payment,
                    forceUpdate = forceUpdate,
                    channelId = channelId,
                )
            }

            is PaymentKind.Bolt11 -> {
                processBolt11(kind = kind, payment = payment, state = state)
            }

            else -> Unit // Handle spontaneous payments if needed
        }
    }

    private fun processBolt11(
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
            LightningActivity.create(
                id = payment.id,
                txType = payment.direction.toPaymentType(),
                status = state,
                value = payment.amountSats ?: 0u,
                invoice = kind.bolt11 ?: "Loading...",
                timestamp = payment.latestUpdateTimestamp,
                fee = (payment.feePaidMsat ?: 0u) / 1000u,
                message = kind.description.orEmpty(),
                preimage = kind.preimage,
                seenAt = null, // TODO implement synonymdev/bitkit-ios#270 changes
            )
        }

        if (getActivityById(payment.id) != null) {
            updateActivity(payment.id, Activity.Lightning(ln))
        } else {
            upsertActivity(Activity.Lightning(ln))
        }
    }

    /**
     * Check pre-activity metadata for addresses in the transaction
     * Returns the first address found in pre-activity metadata that matches a transaction output
     */
    private suspend fun findAddressInPreActivityMetadata(
        details: TransactionDetails
    ): String? {
        for (output in details.outputs) {
            val address = output.scriptpubkeyAddress ?: continue
            val metadata = coreService.activity.getPreActivityMetadata(
                searchKey = address,
                searchByAddress = true
            )
            if (metadata != null && metadata.isReceive) {
                return address
            }
        }
        return null
    }

    private suspend fun resolveAddressForInboundPayment(
        kind: PaymentKind.Onchain,
        existingActivity: Activity?,
        payment: PaymentDetails,
        transactionDetails: TransactionDetails? = null,
    ): String? {
        if (existingActivity != null || payment.direction != PaymentDirection.INBOUND) {
            return null
        }

        // Get transaction details if not provided
        val details = transactionDetails ?: lightningService.getTransactionDetails(kind.txid)
        if (details == null) {
            Logger.verbose("Transaction details not available for txid: ${kind.txid}", context = TAG)
            return null
        }

        return findAddressInPreActivityMetadata(details)
    }

    private data class ConfirmationData(
        val isConfirmed: Boolean,
        val confirmedTimestamp: ULong?,
        val timestamp: ULong,
    )

    private fun getConfirmationStatus(
        kind: PaymentKind.Onchain,
        timestamp: ULong,
    ): ConfirmationData {
        var isConfirmed = false
        var confirmedTimestamp: ULong? = null

        val status = kind.status
        if (status is ConfirmationStatus.Confirmed) {
            isConfirmed = true
            confirmedTimestamp = status.timestamp
        }

        if (isConfirmed && confirmedTimestamp != null && confirmedTimestamp < timestamp) {
            confirmedTimestamp = timestamp
        }

        return ConfirmationData(isConfirmed, confirmedTimestamp, timestamp)
    }

    private fun buildUpdatedOnchainActivity(
        existingActivity: Activity.Onchain,
        confirmationData: ConfirmationData,
        channelId: String? = null,
    ): OnchainActivity {
        var preservedIsTransfer = existingActivity.v1.isTransfer
        var preservedChannelId = existingActivity.v1.channelId

        if ((preservedChannelId == null || !preservedIsTransfer) && channelId != null) {
            preservedChannelId = channelId
            preservedIsTransfer = true
        }

        val finalDoesExist = if (confirmationData.isConfirmed) true else existingActivity.v1.doesExist

        val updatedOnChain = existingActivity.v1.copy(
            confirmed = confirmationData.isConfirmed,
            confirmTimestamp = confirmationData.confirmedTimestamp,
            doesExist = finalDoesExist,
            updatedAt = confirmationData.timestamp,
            isTransfer = preservedIsTransfer,
            channelId = preservedChannelId,
        )

        return updatedOnChain
    }

    private fun buildNewOnchainActivity(
        payment: PaymentDetails,
        kind: PaymentKind.Onchain,
        confirmationData: ConfirmationData,
        resolvedAddress: String?,
        channelId: String? = null,
    ): OnchainActivity {
        val isTransfer = channelId != null

        return OnchainActivity.create(
            id = payment.id,
            txType = payment.direction.toPaymentType(),
            txId = kind.txid,
            value = payment.amountSats ?: 0u,
            fee = (payment.feePaidMsat ?: 0u) / 1000u,
            address = resolvedAddress ?: "Loading...",
            timestamp = confirmationData.timestamp,
            confirmed = confirmationData.isConfirmed,
            isTransfer = isTransfer,
            confirmTimestamp = confirmationData.confirmedTimestamp,
            channelId = channelId,
            seenAt = null, // TODO implement synonymdev/bitkit-ios#270 changes
        )
    }

    private suspend fun processOnchainPayment(
        kind: PaymentKind.Onchain,
        payment: PaymentDetails,
        forceUpdate: Boolean,
        channelId: String? = null,
        transactionDetails: TransactionDetails? = null,
    ) {
        val timestamp = payment.latestUpdateTimestamp
        val confirmationData = getConfirmationStatus(kind, timestamp)

        val existingActivity = getActivityById(payment.id)
        if (existingActivity != null &&
            existingActivity is Activity.Onchain &&
            (existingActivity.v1.updatedAt ?: 0u) > payment.latestUpdateTimestamp
        ) {
            return
        }

        var resolvedChannelId = channelId

        // Check if this transaction is a channel transfer
        if (resolvedChannelId == null) {
            val foundChannelId = findChannelForTransaction(
                txid = kind.txid,
                direction = payment.direction,
                transactionDetails = transactionDetails
            )
            if (foundChannelId != null) {
                resolvedChannelId = foundChannelId
            }
        }

        val resolvedAddress = resolveAddressForInboundPayment(kind, existingActivity, payment, transactionDetails)

        val onChain = if (existingActivity is Activity.Onchain) {
            buildUpdatedOnchainActivity(
                existingActivity = existingActivity,
                confirmationData = confirmationData,
                channelId = resolvedChannelId,
            )
        } else {
            buildNewOnchainActivity(
                payment = payment,
                kind = kind,
                confirmationData = confirmationData,
                resolvedAddress = resolvedAddress,
                channelId = resolvedChannelId,
            )
        }

        if (onChain.id in cacheStore.data.first().deletedActivities && !forceUpdate) {
            Logger.verbose("Activity ${onChain.id} was already deleted, skipping", context = TAG)
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
                val isLightning = Random.nextBoolean()
                val value = (1000..1_000_000).random().toULong()
                val txTimestamp =
                    (timestamp.toLong() - (0..30L * 24 * 60 * 60).random()).toULong() // Random time in last 30 days
                val txType = if (Random.nextBoolean()) PaymentType.SENT else PaymentType.RECEIVED
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
                        LightningActivity.create(
                            id = id,
                            txType = txType,
                            status = status,
                            value = value,
                            invoice = "lnbc$value",
                            timestamp = txTimestamp,
                            fee = (1..1_000).random().toULong(),
                            message = possibleMessages.random(),
                            preimage = if (Random.nextBoolean()) "preimage$i" else null,
                        )
                    )
                } else {
                    id = "test-onchain-$i"
                    activity = Activity.Onchain(
                        OnchainActivity.create(
                            id = id,
                            txType = txType,
                            txId = "a".repeat(64), // Mock txid
                            value = value,
                            fee = (100..10_000).random().toULong(),
                            address = "bc1...$i",
                            timestamp = txTimestamp,
                            confirmed = Random.nextBoolean(),
                            feeRate = (1..100).random().toULong(),
                            isBoosted = Random.nextBoolean(),
                            isTransfer = Random.nextBoolean(),
                            confirmTimestamp = if (Random.nextBoolean()) txTimestamp + 3600.toULong() else null,
                            channelId = if (Random.nextBoolean()) "channel$i" else null,
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

    suspend fun handleOnchainTransactionReceived(txid: String, details: TransactionDetails) {
        ServiceQueue.CORE.background {
            runCatching {
                val payments = lightningService.payments ?: run {
                    Logger.warn("No payments available for transaction $txid", context = TAG)
                    return@background
                }

                val payment = payments.firstOrNull { payment ->
                    (payment.kind as? PaymentKind.Onchain)?.txid == txid
                } ?: run {
                    Logger.warn("Payment not found for transaction $txid", context = TAG)
                    return@background
                }

                processOnchainPayment(
                    kind = payment.kind as PaymentKind.Onchain,
                    payment = payment,
                    forceUpdate = false,
                    channelId = null,
                    transactionDetails = details,
                )
            }.onFailure { e ->
                Logger.error("Error handling onchain transaction received for $txid", e, context = TAG)
            }
        }
    }

    suspend fun handleOnchainTransactionConfirmed(txid: String, details: TransactionDetails) {
        ServiceQueue.CORE.background {
            runCatching {
                val payments = lightningService.payments ?: run {
                    Logger.warn("No payments available for transaction $txid", context = TAG)
                    return@background
                }

                val payment = payments.firstOrNull { payment ->
                    (payment.kind as? PaymentKind.Onchain)?.txid == txid
                } ?: run {
                    Logger.warn("Payment not found for transaction $txid", context = TAG)
                    return@background
                }

                processOnchainPayment(
                    kind = payment.kind as PaymentKind.Onchain,
                    payment = payment,
                    forceUpdate = false,
                    channelId = null,
                    transactionDetails = details,
                )
            }.onFailure { e ->
                Logger.error("Error handling onchain transaction confirmed for $txid", e, context = TAG)
            }
        }
    }

    suspend fun handleOnchainTransactionReplaced(txid: String, conflicts: List<String>) {
        ServiceQueue.CORE.background {
            runCatching {
                val replacedActivity = getOnchainActivityByTxId(txid)
                markReplacedActivity(txid, replacedActivity, conflicts)

                for (conflictTxid in conflicts) {
                    val replacementActivity = getOrCreateReplacementActivity(conflictTxid)
                    if (replacementActivity != null && !replacementActivity.boostTxIds.contains(txid)) {
                        updateReplacementActivity(txid, conflictTxid, replacementActivity, replacedActivity)
                    }
                }
            }.onFailure { e ->
                Logger.error("Error handling onchain transaction replaced for $txid", e, context = TAG)
            }
        }
    }

    private fun markReplacedActivity(
        txid: String,
        replacedActivity: OnchainActivity?,
        conflicts: List<String>,
    ) {
        if (replacedActivity != null) {
            Logger.info(
                "Transaction $txid replaced by ${conflicts.size} conflict(s): " +
                    conflicts.joinToString(", "),
                context = TAG
            )

            val updatedActivity = replacedActivity.copy(
                doesExist = false,
                isBoosted = false,
                updatedAt = System.currentTimeMillis().toULong() / 1000u
            )
            updateActivity(replacedActivity.id, Activity.Onchain(updatedActivity))
            Logger.info("Marked transaction $txid as replaced", context = TAG)
        } else {
            Logger.info(
                "Activity not found for replaced transaction $txid - " +
                    "will be created when transaction is processed",
                context = TAG
            )
        }
    }

    private suspend fun getOrCreateReplacementActivity(conflictTxid: String): OnchainActivity? {
        var replacementActivity = getOnchainActivityByTxId(conflictTxid)

        if (replacementActivity == null) {
            val payments = lightningService.payments
            val replacementPayment = payments?.firstOrNull { payment ->
                (payment.kind as? PaymentKind.Onchain)?.txid == conflictTxid
            }

            if (replacementPayment != null) {
                Logger.info(
                    "Processing replacement transaction $conflictTxid that was already in payments list",
                    context = TAG
                )
                val processResult = runCatching {
                    processOnchainPayment(
                        kind = replacementPayment.kind as PaymentKind.Onchain,
                        payment = replacementPayment,
                        forceUpdate = false,
                        channelId = null,
                    )
                    getOnchainActivityByTxId(conflictTxid)
                }
                processResult.onFailure { e ->
                    Logger.error(
                        "Failed to process replacement transaction $conflictTxid",
                        e,
                        context = TAG
                    )
                }
                replacementActivity = processResult.getOrNull()
            }
        }

        return replacementActivity
    }

    private suspend fun updateReplacementActivity(
        txid: String,
        conflictTxid: String,
        replacementActivity: OnchainActivity,
        replacedActivity: OnchainActivity?,
    ) {
        val updatedActivity = replacementActivity.copy(
            boostTxIds = replacementActivity.boostTxIds + txid,
            isBoosted = true,
            updatedAt = System.currentTimeMillis().toULong() / 1000u
        )
        updateActivity(replacementActivity.id, Activity.Onchain(updatedActivity))

        if (replacedActivity != null) {
            copyTagsFromReplacedActivity(txid, conflictTxid, replacedActivity.id, replacementActivity.id)
        }

        Logger.info("Updated replacement transaction $conflictTxid with boostTxId $txid", context = TAG)
    }

    private suspend fun copyTagsFromReplacedActivity(
        txid: String,
        conflictTxid: String,
        replacedActivityId: String,
        replacementActivityId: String,
    ) {
        runCatching {
            val replacedTags = tags(replacedActivityId)
            if (replacedTags.isNotEmpty()) {
                appendTags(replacementActivityId, replacedTags)
            }
        }.onFailure { e ->
            Logger.error(
                "Failed to copy tags from replaced transaction $txid " +
                    "to replacement transaction $conflictTxid",
                e,
                context = TAG
            )
        }
    }

    suspend fun handleOnchainTransactionReorged(txid: String) {
        ServiceQueue.CORE.background {
            runCatching {
                val onchain = getOnchainActivityByTxId(txid) ?: run {
                    Logger.warn("Activity not found for reorged transaction $txid", context = TAG)
                    return@background
                }

                val updatedActivity = onchain.copy(
                    confirmed = false,
                    confirmTimestamp = null,
                    updatedAt = System.currentTimeMillis().toULong() / 1000u
                )

                updateActivity(onchain.id, Activity.Onchain(updatedActivity))
            }.onFailure { e ->
                Logger.error("Error handling onchain transaction reorged for $txid", e, context = TAG)
            }
        }
    }

    suspend fun handleOnchainTransactionEvicted(txid: String) {
        ServiceQueue.CORE.background {
            runCatching {
                val onchain = getOnchainActivityByTxId(txid) ?: run {
                    Logger.warn("Activity not found for evicted transaction $txid", context = TAG)
                    return@background
                }

                val updatedActivity = onchain.copy(
                    doesExist = false,
                    updatedAt = System.currentTimeMillis().toULong() / 1000u
                )

                updateActivity(onchain.id, Activity.Onchain(updatedActivity))
            }.onFailure { e ->
                Logger.error("Error handling onchain transaction evicted for $txid", e, context = TAG)
            }
        }
    }

    suspend fun shouldShowReceivedSheet(txid: String, value: ULong): Boolean {
        return ServiceQueue.CORE.background {
            if (value == 0uL) {
                return@background false
            }

            if (findClosedChannelForTransaction(txid, null) != null) {
                return@background false
            }

            runCatching {
                val onchain = getOnchainActivityByTxId(txid) ?: return@background true

                if (onchain.boostTxIds.isEmpty()) {
                    return@background true
                }

                for (replacedTxid in onchain.boostTxIds) {
                    val replaced = getOnchainActivityByTxId(replacedTxid)
                    if (replaced != null && replaced.value == value) {
                        Logger.info(
                            "Skipping received sheet for replacement transaction $txid " +
                                "with same value as replaced transaction $replacedTxid",
                            context = TAG
                        )
                        return@background false
                    }
                }
            }.onFailure { e ->
                Logger.error("Failed to check existing activities for replacement", e, context = TAG)
            }

            return@background true
        }
    }

    suspend fun getBoostTxDoesExist(boostTxIds: List<String>): Map<String, Boolean> {
        return ServiceQueue.CORE.background {
            val doesExistMap = mutableMapOf<String, Boolean>()
            for (boostTxId in boostTxIds) {
                val boostActivity = getOnchainActivityByTxId(boostTxId)
                if (boostActivity != null) {
                    doesExistMap[boostTxId] = boostActivity.doesExist
                }
            }
            return@background doesExistMap
        }
    }

    suspend fun isCpfpChildTransaction(txId: String): Boolean {
        return ServiceQueue.CORE.background {
            val txIdsInBoostTxIds = getTxIdsInBoostTxIds()
            if (!txIdsInBoostTxIds.contains(txId)) {
                return@background false
            }

            val activity = getOnchainActivityByTxId(txId) ?: return@background false
            return@background activity.doesExist
        }
    }

    suspend fun getTxIdsInBoostTxIds(): Set<String> {
        return ServiceQueue.CORE.background {
            val allOnchainActivities = get(
                filter = ActivityFilter.ONCHAIN,
                txType = null,
                tags = null,
                search = null,
                minDate = null,
                maxDate = null,
                limit = null,
                sortDirection = null
            )

            allOnchainActivities
                .filterIsInstance<Activity.Onchain>()
                .flatMap { it.v1.boostTxIds }
                .toSet()
        }
    }

    private suspend fun findChannelForTransaction(
        txid: String,
        direction: PaymentDirection,
        transactionDetails: TransactionDetails? = null,
    ): String? {
        return when (direction) {
            PaymentDirection.INBOUND -> {
                // Check if this transaction is a channel close by checking if it spends
                // a closed channel's funding UTXO
                findClosedChannelForTransaction(txid, transactionDetails)
            }
            PaymentDirection.OUTBOUND -> {
                // Check if this transaction is a channel open by checking if it's
                // the funding transaction for an open channel
                findOpenChannelForTransaction(txid)
            }
        }
    }

    private suspend fun findOpenChannelForTransaction(txid: String): String? {
        val channels = lightningService.channels ?: return null
        if (channels.isEmpty()) return null

        // First, check if the transaction matches any channel's funding transaction directly
        val directMatch = channels.firstOrNull { channel ->
            channel.fundingTxo?.txid == txid
        }
        if (directMatch != null) {
            return directMatch.channelId
        }

        // If no direct match, check Blocktank orders for payment transactions
        return findChannelFromBlocktankOrders(txid, channels)
    }

    private suspend fun findChannelFromBlocktankOrders(
        txid: String,
        channels: List<ChannelDetails>,
    ): String? {
        return runCatching {
            val blocktank = coreService.blocktank
            val orders = blocktank.orders(orderIds = null, filter = null, refresh = false)
            val matchingOrder = orders.firstOrNull { order ->
                order.payment?.onchain?.transactions?.any { transaction -> transaction.txId == txid } == true
            } ?: return null

            val orderChannel = matchingOrder.channel ?: return null
            channels.firstOrNull { channel ->
                channel.fundingTxo?.txid == orderChannel.fundingTx.id
            }?.channelId
        }.onFailure { e ->
            Logger.warn("Failed to fetch Blocktank orders: $e", context = TAG)
        }.getOrNull()
    }

    suspend fun findClosedChannelForTransaction(txid: String, transactionDetails: TransactionDetails? = null): String? {
        return runCatching {
            val closedChannelsList = closedChannels(SortDirection.DESC)
            if (closedChannelsList.isEmpty()) {
                return null
            }

            // Use provided transaction details if available, otherwise try node
            val details = transactionDetails ?: lightningService.getTransactionDetails(txid) ?: run {
                Logger.warn("Transaction details not available for $txid", context = TAG)
                return null
            }

            for (input in details.inputs) {
                val inputTxid = input.txid
                val inputVout = input.vout.toInt()

                val matchingChannel = closedChannelsList.firstOrNull { channel ->
                    channel.fundingTxoTxid == inputTxid && channel.fundingTxoIndex == inputVout.toUInt()
                }

                if (matchingChannel != null) {
                    return matchingChannel.channelId
                }
            }
            null
        }.onFailure { e ->
            Logger.warn("Failed to check if transaction $txid spends closed channel funding UTXO", e, context = TAG)
        }.getOrNull()
    }

    companion object {
        private const val TAG = "ActivityService"
    }
}

// endregion

// region Blocktank

class BlocktankService(
    @Suppress("unused") private val coreService: CoreService, // used to ensure CoreService inits first
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

    suspend fun cjitEntries(
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

    suspend fun setInfo(info: IBtInfo) = ServiceQueue.CORE.background {
        upsertInfo(info)
    }

    suspend fun upsertOrderList(orders: List<IBtOrder>) = ServiceQueue.CORE.background {
        upsertOrders(orders)
    }

    suspend fun upsertCjitList(cjitEntries: List<IcJitEntry>) = ServiceQueue.CORE.background {
        upsertCjitEntries(cjitEntries)
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
