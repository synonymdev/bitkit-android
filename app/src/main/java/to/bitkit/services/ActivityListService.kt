package to.bitkit.services

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.Network
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentStatus
import to.bitkit.async.BaseCoroutineScope
import to.bitkit.async.ServiceQueue
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.env.Tag.APP
import to.bitkit.ext.amountSats
import to.bitkit.shared.AppError
import uniffi.bitkitcore.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random.Default.nextBoolean

@Singleton
class ActivityListService @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
) : BaseCoroutineScope(bgDispatcher) {
    private val walletIndex: Int = 0

    init {
        launch {
            try {
                initializeDatabase()
            } catch (e: Exception) {
                Log.e(APP, "Failed to initialize bitkit-core db", e)
            }
        }
    }

    private suspend fun initializeDatabase() {
        val dbPath = Env.bitkitCoreStoragePath(walletIndex)
        ServiceQueue.ACTIVITY.background {
            initDb(basePath = dbPath)
        }
    }

    // MARK: - Database Management

    suspend fun removeAll() {
        ServiceQueue.ACTIVITY.background {
            // Only allow removing on regtest for now
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

    // MARK: - Activity Methods

    suspend fun insert(activity: Activity) {
        ServiceQueue.ACTIVITY.background {
            insertActivity(activity)
        }
    }

    suspend fun syncLdkNodePayments(payments: List<PaymentDetails>) {
        ServiceQueue.ACTIVITY.background {
            var addedCount = 0
            var updatedCount = 0

            for (payment in payments) {
                // Skip pending inbound payments, just means they created an invoice
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

            Log.i("Synced LDK payments - Added: $addedCount, Updated: $updatedCount", "ActivityListService")
        }
    }

    suspend fun getActivity(id: String): Activity? {
        return ServiceQueue.ACTIVITY.background {
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
        return ServiceQueue.ACTIVITY.background {
            getActivities(filter, txType, tags, search, minDate, maxDate, limit, sortDirection)
        }
    }

    suspend fun update(id: String, activity: Activity) {
        ServiceQueue.ACTIVITY.background {
            updateActivity(id, activity)
        }
    }

    suspend fun delete(id: String): Boolean {
        return ServiceQueue.ACTIVITY.background {
            deleteActivityById(id)
        }
    }

    // MARK: - Tag Methods

    suspend fun addTags(toActivityId: String, tags: List<String>) {
        ServiceQueue.ACTIVITY.background {
            uniffi.bitkitcore.addTags(toActivityId, tags)
        }
    }

    suspend fun removeTags(fromActivityId: String, tags: List<String>) {
        ServiceQueue.ACTIVITY.background {
            uniffi.bitkitcore.removeTags(fromActivityId, tags)
        }
    }

    suspend fun getTags(forActivityId: String): List<String> {
        return ServiceQueue.ACTIVITY.background {
            uniffi.bitkitcore.getTags(forActivityId)
        }
    }

    suspend fun getAllUniqueTags(): List<String> {
        return ServiceQueue.ACTIVITY.background {
            uniffi.bitkitcore.getAllUniqueTags()
        }
    }

    suspend fun generateRandomTestData(count: Int = 100) {
        if (Env.network != Network.REGTEST) {
            throw AppError(message = "Regtest only")
        }
        ServiceQueue.ACTIVITY.background {
            val timestamp = (System.currentTimeMillis() / 1000).toULong()
            val possibleTags =
                listOf("coffee", "food", "shopping", "transport", "entertainment", "work", "friends", "family")
            val possibleMessages = listOf(
                "Coffee at Starbucks", "Lunch with friends", "Uber ride", "Movie tickets", "Groceries",
                "Work payment", "Gift for mom", "Split dinner bill", "Monthly rent", "Gym membership"
            )

            repeat(count) { i ->
                val isLightning = nextBoolean()
                val value = (1000..1_000_000).random().toULong()
                val txTimestamp = timestamp - (0..2_592_000).random().toULong() // Random time in last 30 days
                val txType = if (nextBoolean()) PaymentType.SENT else PaymentType.RECEIVED
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
                            preimage = if (nextBoolean()) "preimage$i" else null,
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
                            confirmed = nextBoolean(),
                            timestamp = txTimestamp,
                            isBoosted = nextBoolean(),
                            isTransfer = nextBoolean(),
                            doesExist = true,
                            confirmTimestamp = if (nextBoolean()) txTimestamp + 3600.toULong() else null,
                            channelId = if (nextBoolean()) "channel$i" else null,
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
                    addTags(id, tags)
                }
            }
        }
    }
}
