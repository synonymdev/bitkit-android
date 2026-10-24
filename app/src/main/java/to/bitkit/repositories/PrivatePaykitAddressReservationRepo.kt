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
import to.bitkit.data.CacheStore
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
import to.bitkit.services.AddressDerivationInfo
import to.bitkit.services.CoreService
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

sealed class PrivatePaykitAddressReservationError(message: String) : AppError(message) {
    data object AddressReservationFailed : PrivatePaykitAddressReservationError(
        "Unable to reserve Paykit address",
    )
}

@Singleton
class PrivatePaykitAddressReservationRepo @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val reservationStore: PrivatePaykitReservationStore,
    private val settingsStore: SettingsStore,
    private val coreService: CoreService,
    private val lightningRepo: LightningRepo,
    private val cacheStore: CacheStore,
) {
    companion object {
        private const val TAG = "PrivatePaykitAddressReservationRepo"
    }

    private val mutex = Mutex()
    private val allocationMutex = Mutex()

    @Volatile
    internal var attributionVersion = 0L
        private set
    private var ledger: PrivatePaykitReservationData? = null
        set(value) {
            if (field != value) attributionVersion++
            field = value
        }

    private val _backupStateVersion = MutableStateFlow(0L)
    val backupStateVersion: StateFlow<Long> = _backupStateVersion.asStateFlow()

    suspend fun backupSnapshot(): Result<Map<String, Int>?> = withContext(ioDispatcher) {
        runSuspendCatching {
            quarantineLegacyPublicAddress()
            val snapshot = locked { reservationCeilings(it) }
            snapshot.takeIf { it.isNotEmpty() }
        }
    }

    suspend fun quarantinedPublicAddressesSnapshot(): Set<String> = withContext(ioDispatcher) {
        quarantineLegacyPublicAddress()
        locked { it.quarantinedPublicAddresses }
    }

    suspend fun restoreBackup(
        highestReservedReceiveIndexByAddressType: Map<String, Int>?,
        quarantinedPublicAddresses: Set<String> = emptySet(),
    ): Result<Unit> =
        withContext(ioDispatcher) {
            allocationMutex.withLock {
                runSuspendCatching {
                    quarantineLegacyPublicAddress()
                    locked { current ->
                        val restored = highestReservedReceiveIndexByAddressType
                            ?.filterValues { it >= 0 }
                            .orEmpty()
                        val previous = reservationCeilings(current)
                        val ceilings = (restored.keys + previous.keys).associateWith {
                            maxOf(restored[it] ?: -1, previous[it] ?: -1)
                        }
                        val next = PrivatePaykitReservationData(
                            restoredReservedReceiveIndexCeilingsByAddressType = ceilings,
                            quarantinedPublicAddresses = current.quarantinedPublicAddresses +
                                quarantinedPublicAddresses,
                        )
                        persist(next)
                        ledger = next
                    }
                    notifyBackupStateChanged()
                }
            }.onFailure {
                Logger.error("Failed to restore Paykit reservations", it, context = TAG)
            }
        }

    suspend fun currentOrRotatedAddress(
        publicKey: String,
    ): Result<String> = withContext(ioDispatcher) {
        allocationMutex.withLock {
            runSuspendCatching {
                quarantineLegacyPublicAddress()
                val assignmentKey = normalizedPublicKey(publicKey)
                val current = locked { it.contactAssignments[assignmentKey] }
                if (current != null && isAddressTypeMonitored(current.addressType)) {
                    val address = resolvedAddress(current).getOrThrow()
                    if (!isQuarantinedPublicAddress(address) && !isReservedAddressUsed(address)) {
                        return@runSuspendCatching address
                    }
                } else if (current != null) {
                    clearCurrentAssignment(assignmentKey)
                }

                allocateAddress(assignmentKey, isPublic = false).getOrThrow()
            }
        }.onFailure {
            Logger.warn(
                "Failed to get private Paykit address for '${redacted(publicKey)}'",
                it,
                context = TAG,
            )
        }
    }

    suspend fun currentOrRotatedPublicAddress(publicKey: String): Result<String> = withContext(ioDispatcher) {
        allocationMutex.withLock {
            runSuspendCatching {
                quarantineLegacyPublicAddress()
                val assignmentKey = normalizedPublicKey(publicKey)
                val current = locked { it.publicAssignments[assignmentKey] }
                if (current != null && isAddressTypeMonitored(current.addressType)) {
                    val address = resolvedAddress(current).getOrThrow()
                    if (!isReservedAddressUsed(address)) return@runSuspendCatching address
                }
                allocateAddress(assignmentKey, isPublic = true).getOrThrow()
            }
        }
    }

    /** Keeps the read-only match stable through acknowledgement; the callback must not re-enter reservations. */
    internal suspend fun withPublicAddressMatch(
        publicKey: String,
        address: String,
        acknowledge: suspend (Boolean) -> Unit,
    ) = withContext(ioDispatcher) {
        locked {
            val matches = address.isNotBlank() &&
                it.publicAssignments[normalizedPublicKey(publicKey)]?.address == address
            acknowledge(matches)
        }
    }

    suspend fun nextReusableReceiveAddress(): Result<String> =
        nextReusableReceiveAddress(selectedAddressType())

    suspend fun nextReusableReceiveAddress(addressType: AddressType): Result<String> =
        withContext(ioDispatcher) {
            allocationMutex.withLock {
                runSuspendCatching {
                    quarantineLegacyPublicAddress()
                    nextAvailableAddress(addressType).address
                }
            }.onFailure {
                Logger.error("Failed to create non-reserved receive address", it, context = TAG)
            }
        }

    suspend fun reconcileReservedIndexesWithLdk(): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            locked { reservationCeilings(it) }.forEach { (addressTypeKey, highestReserved) ->
                val addressType = addressTypeKey.toAddressType() ?: return@forEach
                if (!isAddressTypeMonitored(addressTypeKey)) return@forEach
                reconcileAddressTypeWithLdk(addressType, highestReserved).getOrThrow()
            }
        }.onFailure {
            Logger.warn("Failed to reconcile Paykit address reservations", it, context = TAG)
        }
    }

    suspend fun isUnavailableForReusableReceive(address: String): Boolean = withContext(ioDispatcher) {
        quarantineLegacyPublicAddress()
        if (address.isBlank()) return@withContext false
        if (isQuarantinedPublicAddress(address)) return@withContext true
        if (locked { current -> current.publicAssignments.values.any { it.address == address } }) {
            return@withContext true
        }
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
                (assignment.address == address || resolvedAddress(assignment).getOrThrow() == address)
        }?.first
    }

    suspend fun currentContactPublicKeyForReservedAddress(address: String): String? = withContext(ioDispatcher) {
        if (address.isBlank()) return@withContext null
        val addressType = address.addressTypeFromAddress() ?: return@withContext null
        val assignments = locked { it.contactAssignments.entries.map { entry -> entry.key to entry.value } }
        assignments.firstOrNull { (_, assignment) ->
            assignment.addressType == addressType &&
                (assignment.address == address || resolvedAddress(assignment).getOrNull() == address)
        }?.first
    }

    suspend fun contactsWithUsedReservedAddresses(): List<String> = withContext(ioDispatcher) {
        val assignments = locked { it.contactAssignments.map { entry -> entry.key to entry.value } }
        assignments.mapNotNull { (publicKey, assignment) ->
            val address = resolvedAddress(assignment).getOrNull() ?: return@mapNotNull null
            val isUsed = runSuspendCatching { isReservedAddressUsed(address) }
                .onFailure {
                    Logger.warn(
                        "Failed to check private Paykit address usage for " +
                            "'${redacted(publicKey)}'",
                        it,
                        context = TAG,
                    )
                }
                .getOrDefault(false)
            publicKey.takeIf { isUsed }
        }.distinct()
    }

    private suspend fun isReservedAddressUsed(address: String): Boolean {
        if (coreService.isAddressUsed(address)) return true
        if (!lightningRepo.lightningState.value.nodeLifecycleState.isRunning()) return false
        return lightningRepo.getAddressBalance(address).getOrDefault(0u) > 0u
    }

    suspend fun clearContactAssignments(
        excludingPublicKeys: Collection<String>,
        isStillCurrent: (() -> Boolean)? = null,
    ) = withContext(ioDispatcher) {
        val savedKeys = excludingPublicKeys.mapNotNull { normalizedPublicKeyOrNull(it) }.toSet()
        locked { current ->
            if (isStillCurrent?.invoke() == false) return@locked
            val next = current.copy(
                contactAssignments = current.contactAssignments.filterKeys {
                    it in savedKeys
                },
                contactAssignmentHistory = current.contactAssignmentHistory.filterKeys {
                    it in savedKeys
                },
            )
            // Keep failed deletion hidden immediately, but retry the durable write even when
            // the filtered in-memory ledger already matches the requested cleanup.
            ledger = next
            persist(next)
            notifyBackupStateChanged()
        }
    }

    suspend fun removeContactAssignments(
        publicKeys: Collection<String>,
        isStillCurrent: (() -> Boolean)? = null,
    ) = withContext(ioDispatcher) {
        val removedKeys = publicKeys.mapNotNull { normalizedPublicKeyOrNull(it) }.toSet()
        locked { current ->
            if (isStillCurrent?.invoke() == false) return@locked
            val next = current.copy(
                contactAssignments = current.contactAssignments.filterKeys { it !in removedKeys },
                contactAssignmentHistory = current.contactAssignmentHistory.filterKeys { it !in removedKeys },
            )
            // Keep failed deletion hidden immediately, but retry the durable write even when
            // the filtered in-memory ledger already matches the requested cleanup.
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

    private suspend fun allocateAddress(
        assignmentKey: String,
        isPublic: Boolean,
    ): Result<String> = withContext(ioDispatcher) {
        runSuspendCatching {
            val addressType = selectedAddressType()
            val addressTypeKey = addressType.toSettingsString()

            val ordinaryAddress = if (isPublic) cacheStore.data.first().onchainAddress else null
            val addressInfo = nextAvailableAddress(addressType, ordinaryAddress)
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
                    publicAssignments = if (isPublic) {
                        current.publicAssignments + (assignmentKey to assignment)
                    } else {
                        current.publicAssignments
                    },
                    contactAssignments = if (isPublic) {
                        current.contactAssignments
                    } else {
                        current.contactAssignments + (assignmentKey to assignment)
                    },
                    contactAssignmentHistory = if (isPublic) {
                        current.contactAssignmentHistory
                    } else {
                        current.contactAssignmentHistory + (assignmentKey to history)
                    },
                )
                persist(next)
                ledger = next
            }
            notifyBackupStateChanged()
            addressInfo.address
        }
    }

    private suspend fun quarantineLegacyPublicAddress() {
        if (!settingsStore.data.first().legacyPublicAddressQuarantinePending) return
        val address = cacheStore.data.first().onchainAddress
        quarantinePublicAddress(address)
        settingsStore.update { it.copy(legacyPublicAddressQuarantinePending = false) }
    }

    suspend fun quarantinePublicAddress(address: String) = withContext(ioDispatcher) {
        if (address.isBlank()) return@withContext
        locked { current ->
            val next = current.copy(quarantinedPublicAddresses = current.quarantinedPublicAddresses + address)
            persist(next)
            ledger = next
        }
        notifyBackupStateChanged()
    }

    private suspend fun nextAvailableAddress(
        addressType: AddressType,
        ordinaryAddress: String? = null,
    ): AddressDerivationInfo {
        prepareReusableReceive(addressType).getOrThrow()
        val addressTypeKey = addressType.toSettingsString()
        var previousIndex = -1
        while (true) {
            val candidate = lightningRepo.newAddressInfoForType(addressType).getOrThrow()
            if (candidate.index <= previousIndex || isUnavailableForReusableReceive(candidate.index, addressTypeKey)) {
                throw PrivatePaykitAddressReservationError.AddressReservationFailed
            }
            previousIndex = candidate.index
            val quarantined = isQuarantinedPublicAddress(candidate.address)
            if (!quarantined && candidate.address != ordinaryAddress) return candidate
            if (quarantined) {
                locked { current ->
                    val reserved = current.reservedReceiveIndexesByAddressType[addressTypeKey].orEmpty() +
                        candidate.index
                    val next = current.copy(
                        reservedReceiveIndexesByAddressType = current.reservedReceiveIndexesByAddressType +
                            (addressTypeKey to reserved),
                    )
                    persist(next)
                    ledger = next
                }
                notifyBackupStateChanged()
            }
            lightningRepo.revealReceiveAddresses(candidate.index, addressType).getOrThrow()
        }
    }

    private suspend fun isQuarantinedPublicAddress(address: String): Boolean =
        locked { address in it.quarantinedPublicAddresses }

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
            val highestReserved = locked { reservationCeilings(it)[addressTypeKey] }
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
            ).getOrThrow()
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

    private fun reservationCeilings(
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

    private fun redacted(publicKey: String): String =
        PubkyPublicKeyFormat.redacted(publicKey)

    private fun PrivatePaykitStoredAssignmentData.assignmentKey(): String = "$addressType:$receiveIndex"
}
