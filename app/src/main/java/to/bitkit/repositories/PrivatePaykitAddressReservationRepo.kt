package to.bitkit.repositories

import com.synonym.bitkitcore.AddressType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import to.bitkit.data.PrivatePaykitReservationData
import to.bitkit.data.PrivatePaykitReservationStore
import to.bitkit.data.PrivatePaykitStoredAssignmentData
import to.bitkit.data.SettingsStore
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.DEFAULT_ADDRESS_TYPE
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.models.addressTypeFromAddress
import to.bitkit.models.toAddressType
import to.bitkit.models.toSettingsString
import to.bitkit.services.CoreService
import to.bitkit.services.PaykitReceiverPaths
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

sealed class PrivatePaykitAddressReservationError(message: String) : AppError(message) {
    data object AddressReservationFailed : PrivatePaykitAddressReservationError(
        "Unable to reserve private Paykit address",
    )
}

@Singleton
@Suppress("TooManyFunctions")
class PrivatePaykitAddressReservationRepo @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val reservationStore: PrivatePaykitReservationStore,
    private val settingsStore: SettingsStore,
    private val coreService: CoreService,
    private val lightningRepo: LightningRepo,
) {
    companion object {
        private const val TAG = "PrivatePaykitAddressReservationRepo"
    }

    private val mutex = Mutex()
    private var ledger: PrivatePaykitReservationData? = null

    private val _backupStateVersion = MutableStateFlow(0L)
    val backupStateVersion: StateFlow<Long> = _backupStateVersion.asStateFlow()

    suspend fun backupSnapshot(): Result<Map<String, Int>?> = withContext(ioDispatcher) {
        runCatching {
            val snapshot = locked { highestReservedReceiveIndexByAddressType(it) }
            snapshot.takeIf { it.isNotEmpty() }
        }
    }

    suspend fun restoreBackup(highestReservedReceiveIndexByAddressType: Map<String, Int>?): Result<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                locked {
                    val restored = highestReservedReceiveIndexByAddressType
                        ?.filterValues { it >= 0 }
                        .orEmpty()
                    val next = PrivatePaykitReservationData(
                        reservedReceiveIndexesByAddressType = emptyMap(),
                        contactAssignments = emptyMap(),
                        contactAssignmentHistory = emptyMap(),
                        restoredReservedReceiveIndexCeilingsByAddressType = restored,
                    )
                    ledger = next
                    persist(next)
                }
                notifyBackupStateChanged()
            }.onFailure {
                Logger.error("Failed to restore private Paykit reservations", it, context = TAG)
            }
        }

    suspend fun currentOrRotatedAddress(
        publicKey: String,
        receiverPath: String,
    ): Result<String> = withContext(ioDispatcher) {
        runSuspendCatching {
            val assignmentKey = contactAssignmentKey(publicKey, receiverPath)
            val current = locked { it.contactAssignments[assignmentKey] }
            if (current != null && isAddressTypeMonitored(current.addressType)) {
                val address = resolvedAddress(current).getOrThrow()
                if (!isReservedAddressUsed(address)) return@runSuspendCatching address
            } else if (current != null) {
                clearCurrentAssignment(assignmentKey)
            }

            allocateAddress(assignmentKey).getOrThrow()
        }.onFailure {
            Logger.warn(
                "Failed to get private Paykit address for '${redacted(publicKey)}'",
                it,
                context = TAG,
            )
        }
    }

    suspend fun nextReusableReceiveAddress(): Result<String> =
        nextReusableReceiveAddress(selectedAddressType())

    suspend fun nextReusableReceiveAddress(addressType: AddressType): Result<String> =
        withContext(ioDispatcher) {
            runCatching {
                prepareReusableReceive(addressType).getOrThrow()
                val addressInfo = lightningRepo.newAddressInfoForType(addressType).getOrThrow()
                if (isUnavailableForReusableReceive(addressInfo.index, addressType.toSettingsString())) {
                    throw PrivatePaykitAddressReservationError.AddressReservationFailed
                }
                addressInfo.address
            }.onFailure {
                Logger.error("Failed to create non-reserved receive address", it, context = TAG)
            }
        }

    suspend fun reconcileReservedIndexesWithLdk(): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            locked { highestReservedReceiveIndexByAddressType(it) }.forEach { (addressTypeKey, highestReserved) ->
                val addressType = addressTypeKey.toAddressType() ?: return@forEach
                if (!isAddressTypeMonitored(addressTypeKey)) return@forEach
                reconcileAddressTypeWithLdk(addressType, highestReserved).getOrThrow()
            }
        }.onFailure {
            Logger.warn("Failed to reconcile private Paykit address reservations", it, context = TAG)
        }
    }

    suspend fun isUnavailableForReusableReceive(address: String): Boolean = withContext(ioDispatcher) {
        if (address.isBlank()) return@withContext false
        val addressType = address.addressTypeFromAddress()?.toAddressType() ?: return@withContext false
        isUnavailableForReusableReceive(address, addressType)
    }

    suspend fun contactPublicKeyForReservedAddress(address: String): String? = withContext(ioDispatcher) {
        if (address.isBlank()) return@withContext null
        val addressType = address.addressTypeFromAddress() ?: return@withContext null

        val assignments = locked { ledger ->
            val current = ledger.contactAssignments.map { it.key to it.value }
            val history = ledger.contactAssignmentHistory.flatMap { (publicKey, assignments) ->
                assignments.map { publicKey to it }
            }
            (current + history).distinctBy { (_, assignment) -> assignment.assignmentKey() }
        }

        assignments.firstOrNull { (_, assignment) ->
            assignment.addressType == addressType &&
                (assignment.address == address || resolvedAddress(assignment).getOrNull() == address)
        }?.first?.publicKeyFromAssignmentKey()
    }

    suspend fun currentContactPublicKeyForReservedAddress(address: String): String? = withContext(ioDispatcher) {
        if (address.isBlank()) return@withContext null
        val addressType = address.addressTypeFromAddress() ?: return@withContext null
        val assignments = locked { it.contactAssignments.entries.map { entry -> entry.key to entry.value } }
        assignments.firstOrNull { (_, assignment) ->
            assignment.addressType == addressType &&
                (assignment.address == address || resolvedAddress(assignment).getOrNull() == address)
        }?.first?.publicKeyFromAssignmentKey()
    }

    suspend fun contactsWithUsedReservedAddresses(): List<String> = withContext(ioDispatcher) {
        val assignments = locked { it.contactAssignments.map { entry -> entry.key to entry.value } }
        assignments.mapNotNull { (publicKey, assignment) ->
            val address = resolvedAddress(assignment).getOrNull() ?: return@mapNotNull null
            val isUsed = runSuspendCatching { isReservedAddressUsed(address) }
                .onFailure {
                    Logger.warn(
                        "Failed to check private Paykit address usage for " +
                            "'${redacted(publicKey.publicKeyFromAssignmentKey())}'",
                        it,
                        context = TAG,
                    )
                }
                .getOrDefault(false)
            publicKey.publicKeyFromAssignmentKey().takeIf { isUsed }
        }.distinct()
    }

    private suspend fun isReservedAddressUsed(address: String): Boolean {
        if (coreService.isAddressUsed(address)) return true
        if (!lightningRepo.lightningState.value.nodeLifecycleState.isRunning()) return false
        return lightningRepo.getAddressBalance(address).getOrDefault(0u) > 0u
    }

    suspend fun hasContactAssignment(publicKey: String): Boolean = withContext(ioDispatcher) {
        val normalizedKey = normalizedPublicKey(publicKey)
        locked {
            it.contactAssignments.keys.any { assignmentKey ->
                assignmentKey.publicKeyFromAssignmentKey() == normalizedKey
            }
        }
    }

    suspend fun clearContactAssignment(publicKey: String) = withContext(ioDispatcher) {
        val normalizedKey = normalizedPublicKey(publicKey)
        locked { current ->
            val hadAssignment = current.contactAssignments.keys.any {
                it.publicKeyFromAssignmentKey() == normalizedKey
            }
            val hadHistory = current.contactAssignmentHistory.keys.any {
                it.publicKeyFromAssignmentKey() == normalizedKey
            }
            if (!hadAssignment && !hadHistory) return@locked
            val next = current.copy(
                contactAssignments = current.contactAssignments.filterKeys {
                    it.publicKeyFromAssignmentKey() != normalizedKey
                },
                contactAssignmentHistory = current.contactAssignmentHistory.filterKeys {
                    it.publicKeyFromAssignmentKey() != normalizedKey
                },
            )
            ledger = next
            persist(next)
            notifyBackupStateChanged()
        }
    }

    suspend fun clearContactAssignments(excludingPublicKeys: Collection<String>) = withContext(ioDispatcher) {
        val savedKeys = excludingPublicKeys.mapNotNull { normalizedPublicKeyOrNull(it) }.toSet()
        locked { current ->
            val next = current.copy(
                contactAssignments = current.contactAssignments.filterKeys {
                    it.publicKeyFromAssignmentKey() in savedKeys
                },
                contactAssignmentHistory = current.contactAssignmentHistory.filterKeys {
                    it.publicKeyFromAssignmentKey() in savedKeys
                },
            )
            if (next == current) return@locked
            ledger = next
            persist(next)
            notifyBackupStateChanged()
        }
    }

    suspend fun clear() = withContext(ioDispatcher) {
        locked {
            ledger = PrivatePaykitReservationData()
            reservationStore.reset()
            notifyBackupStateChanged()
        }
    }

    private suspend fun allocateAddress(assignmentKey: String): Result<String> = withContext(ioDispatcher) {
        runSuspendCatching {
            val addressType = selectedAddressType()
            val addressTypeKey = addressType.toSettingsString()

            prepareReusableReceive(addressType).getOrThrow()
            val addressInfo = lightningRepo.newAddressInfoForType(addressType).getOrThrow()
            if (isUnavailableForReusableReceive(addressInfo.index, addressTypeKey)) {
                throw PrivatePaykitAddressReservationError.AddressReservationFailed
            }
            val assignment = PrivatePaykitStoredAssignmentData(
                addressType = addressTypeKey,
                receiveIndex = addressInfo.index,
                address = addressInfo.address,
            )
            locked { current ->
                val reserved = current.reservedReceiveIndexesByAddressType[addressTypeKey].orEmpty() + addressInfo.index
                val history = current.contactAssignmentHistory[assignmentKey].orEmpty()
                    .let { if (assignment in it) it else it + assignment }
                val next = current.copy(
                    reservedReceiveIndexesByAddressType = current.reservedReceiveIndexesByAddressType +
                        (addressTypeKey to reserved),
                    contactAssignments = current.contactAssignments + (assignmentKey to assignment),
                    contactAssignmentHistory = current.contactAssignmentHistory + (assignmentKey to history),
                )
                ledger = next
                persist(next)
            }
            notifyBackupStateChanged()
            addressInfo.address
        }
    }

    private suspend fun selectedAddressType(): AddressType {
        val settings = settingsStore.data.first()
        return settings.selectedAddressType.toAddressType() ?: DEFAULT_ADDRESS_TYPE
    }

    private suspend fun isAddressTypeMonitored(addressType: String): Boolean {
        val settings = settingsStore.data.first()
        return addressType == settings.selectedAddressType || addressType in settings.addressTypesToMonitor
    }

    private suspend fun isUnavailableForReusableReceive(receiveIndex: Int, addressType: String): Boolean {
        val current = locked { it }
        if (receiveIndex in current.reservedReceiveIndexesByAddressType[addressType].orEmpty()) return true
        return receiveIndex <= (current.restoredReservedReceiveIndexCeilingsByAddressType[addressType] ?: -1)
    }

    private suspend fun prepareReusableReceive(addressType: AddressType): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            val addressTypeKey = addressType.toSettingsString()
            val highestReserved = locked { highestReservedReceiveIndexByAddressType(it)[addressTypeKey] }
                ?: return@runCatching
            reconcileAddressTypeWithLdk(addressType, highestReserved).getOrThrow()
        }
    }

    private suspend fun reconcileAddressTypeWithLdk(addressType: AddressType, highestReserved: Int): Result<Unit> =
        lightningRepo.revealReceiveAddresses(toReceiveIndex = highestReserved, forType = addressType)

    private suspend fun isUnavailableForReusableReceive(address: String, addressType: AddressType): Boolean {
        val addressTypeKey = addressType.toSettingsString()
        val reservedIndexes = locked { it.reservedReceiveIndexesByAddressType[addressTypeKey].orEmpty() }
        return reservedIndexes.any { receiveIndex ->
            val reservedAddress = resolvedAddress(
                PrivatePaykitStoredAssignmentData(
                    addressType = addressTypeKey,
                    receiveIndex = receiveIndex,
                ),
            ).getOrNull()
            reservedAddress == address
        }
    }

    private suspend fun clearCurrentAssignment(assignmentKey: String) {
        locked { current ->
            val next = current.copy(contactAssignments = current.contactAssignments - assignmentKey)
            ledger = next
            persist(next)
            notifyBackupStateChanged()
        }
    }

    private suspend fun resolvedAddress(assignment: PrivatePaykitStoredAssignmentData): Result<String> =
        withContext(ioDispatcher) {
            runCatching {
                assignment.address.takeIf { it.isNotBlank() } ?: lightningRepo.addressInfoForType(
                    addressType = assignment.addressType.toAddressType()
                        ?: throw PrivatePaykitAddressReservationError.AddressReservationFailed,
                    receiveIndex = assignment.receiveIndex,
                ).getOrThrow().address
            }
        }

    private suspend fun ensureLedger(): PrivatePaykitReservationData {
        ledger?.let { return it }
        return reservationStore.data.first().also { ledger = it }
    }

    private suspend fun <T> locked(block: suspend (PrivatePaykitReservationData) -> T): T {
        return mutex.withLock { block(ensureLedger()) }
    }

    private suspend fun persist(data: PrivatePaykitReservationData) {
        reservationStore.update { data }
    }

    private fun highestReservedReceiveIndexByAddressType(
        ledger: PrivatePaykitReservationData,
    ): Map<String, Int> {
        val reserved = ledger.reservedReceiveIndexesByAddressType
            .mapValues { (_, indexes) -> indexes.maxOrNull() ?: -1 }
            .filterValues { it >= 0 }
        return (reserved.keys + ledger.restoredReservedReceiveIndexCeilingsByAddressType.keys)
            .associateWith {
                maxOf(
                    reserved[it] ?: -1,
                    ledger.restoredReservedReceiveIndexCeilingsByAddressType[it] ?: -1,
                )
            }
            .filterValues { it >= 0 }
    }

    private fun notifyBackupStateChanged() {
        _backupStateVersion.update { it + 1 }
    }

    private fun normalizedPublicKey(publicKey: String): String =
        normalizedPublicKeyOrNull(publicKey)
            ?: throw PrivatePaykitAddressReservationError.AddressReservationFailed

    private fun normalizedPublicKeyOrNull(publicKey: String): String? =
        PubkyPublicKeyFormat.normalized(publicKey)

    private fun contactAssignmentKey(publicKey: String, receiverPath: String): String {
        val normalizedKey = normalizedPublicKey(publicKey)
        return if (receiverPath == PaykitReceiverPaths.WALLET) normalizedKey else "$normalizedKey#$receiverPath"
    }

    private fun String.publicKeyFromAssignmentKey(): String = substringBefore("#")

    private fun redacted(publicKey: String): String =
        PubkyPublicKeyFormat.redacted(publicKey)

    private fun PrivatePaykitStoredAssignmentData.assignmentKey(): String = "$addressType:$receiveIndex"
}
