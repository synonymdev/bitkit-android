package to.bitkit.repositories

import com.synonym.bitkitcore.AddressType
import com.synonym.bitkitcore.TransactionDetails
import com.synonym.bitkitcore.TxOutput
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runCurrent
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.async.ServiceQueue
import to.bitkit.data.AppCacheData
import to.bitkit.data.CacheStore
import to.bitkit.data.PrivatePaykitReservationData
import to.bitkit.data.PrivatePaykitReservationStore
import to.bitkit.data.PrivatePaykitStoredAssignmentData
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.keychain.Keychain
import to.bitkit.models.BackupCategory
import to.bitkit.models.NodeLifecycleState
import to.bitkit.services.AddressDerivationInfo
import to.bitkit.services.CoreService
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrivatePaykitAddressReservationRepoTest : BaseUnitTest() {
    companion object {
        private const val CONTACT_KEY = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        private const val PRIVATE_ADDRESS = "bcrt1qterdweva9vextackckt6pjy0mmuc54g87g6lsq"
    }

    private val reservationStore = mock<PrivatePaykitReservationStore>()
    private val settingsStore = mock<SettingsStore>()
    private val coreService = mock<CoreService>()
    private val lightningRepo = mock<LightningRepo>()
    private val cacheStore = mock<CacheStore>()
    private val keychain = mock<Keychain>()
    private val keychainValues = mutableMapOf<String, String>()
    private val cacheData = MutableStateFlow(AppCacheData())
    private val reservationData = MutableStateFlow(PrivatePaykitReservationData())
    private val settingsData = MutableStateFlow(SettingsData())
    private val lightningState = MutableStateFlow(
        LightningState(nodeLifecycleState = NodeLifecycleState.Running),
    )

    private lateinit var sut: PrivatePaykitAddressReservationRepo

    @Before
    fun setUp() {
        whenever(reservationStore.data).thenReturn(reservationData)
        whenever { reservationStore.update(any()) }.thenAnswer {
            val transform = it.getArgument<(PrivatePaykitReservationData) -> PrivatePaykitReservationData>(0)
            reservationData.value = transform(reservationData.value)
        }
        whenever(settingsStore.data).thenReturn(settingsData)
        whenever { settingsStore.update(any()) }.thenAnswer {
            val transform = it.getArgument<(SettingsData) -> SettingsData>(0)
            settingsData.update(transform)
        }
        whenever(cacheStore.data).thenReturn(cacheData)
        whenever { cacheStore.update(any()) }.thenAnswer {
            val transform = it.getArgument<(AppCacheData) -> AppCacheData>(0)
            cacheData.update(transform)
        }
        whenever(lightningRepo.lightningState).thenReturn(lightningState)
        whenever(keychain.exists(any())).thenAnswer { it.getArgument<String>(0) in keychainValues }
        whenever(keychain.loadString(any())).thenAnswer { keychainValues[it.getArgument<String>(0)] }
        whenever { keychain.upsertString(any(), any()) }.thenAnswer {
            keychainValues[it.getArgument(0)] = it.getArgument(1)
            Unit
        }
        whenever { keychain.delete(any()) }.thenAnswer {
            keychainValues.remove(it.getArgument<String>(0))
            Unit
        }

        sut = PrivatePaykitAddressReservationRepo(
            ioDispatcher = testDispatcher,
            reservationStore = reservationStore,
            settingsStore = settingsStore,
            coreService = coreService,
            lightningRepo = lightningRepo,
            cacheStore = cacheStore,
            keychain = keychain,
        )
    }

    @Test
    fun `public addresses exclude Receive and private assignments without contact attribution`() = test {
        cacheData.value = AppCacheData(onchainAddress = "bcrt1qreceive")
        whenever(lightningRepo.newAddressInfoForType(AddressType.P2WPKH)).thenReturn(
            Result.success(AddressDerivationInfo("bcrt1qreceive", 0)),
            Result.success(AddressDerivationInfo("bcrt1qpublic", 1)),
            Result.success(AddressDerivationInfo(PRIVATE_ADDRESS, 2)),
            Result.success(AddressDerivationInfo("bcrt1qnext", 3)),
        )
        whenever(lightningRepo.revealReceiveAddresses(any(), any())).thenReturn(Result.success(Unit))

        assertEquals("bcrt1qpublic", sut.currentOrRotatedPublicAddress(CONTACT_KEY).getOrThrow())
        assertEquals(PRIVATE_ADDRESS, sut.currentOrRotatedAddress(CONTACT_KEY).getOrThrow())
        assertEquals("bcrt1qnext", sut.nextReusableReceiveAddress().getOrThrow())
        assertEquals(setOf(1, 2), reservationData.value.reservedReceiveIndexesByAddressType["nativeSegwit"])
        assertEquals(1, reservationData.value.publicAssignments[CONTACT_KEY]?.receiveIndex)
        assertEquals(2, reservationData.value.contactAssignments[CONTACT_KEY]?.receiveIndex)
        assertNull(sut.contactPublicKeyForReservedAddress("bcrt1qpublic"))
        assertEquals("bcrt1qreceive", cacheData.value.onchainAddress)
    }

    @Test
    fun `public assignment survives restart and rotates without releasing exposed indexes`() = test {
        whenever(coreService.isAddressUsed(any())).thenReturn(false)
        whenever(lightningRepo.newAddressInfoForType(AddressType.P2WPKH)).thenReturn(
            Result.success(AddressDerivationInfo("bcrt1qpublic", 1)),
            Result.success(AddressDerivationInfo("bcrt1qrotated", 2)),
        )
        whenever(lightningRepo.revealReceiveAddresses(any(), any())).thenReturn(Result.success(Unit))
        whenever(lightningRepo.getAddressBalance(any())).thenReturn(Result.success(0u))
        assertEquals("bcrt1qpublic", sut.currentOrRotatedPublicAddress(CONTACT_KEY).getOrThrow())
        sut = PrivatePaykitAddressReservationRepo(
            testDispatcher, reservationStore, settingsStore, coreService, lightningRepo, cacheStore, keychain,
        )
        assertEquals("bcrt1qpublic", sut.currentOrRotatedPublicAddress(CONTACT_KEY).getOrThrow())
        whenever(coreService.isAddressUsed("bcrt1qpublic")).thenReturn(true)
        assertEquals("bcrt1qrotated", sut.currentOrRotatedPublicAddress(CONTACT_KEY).getOrThrow())
        sut.removeContactAssignments(listOf(CONTACT_KEY))
        assertEquals(setOf(1, 2), reservationData.value.reservedReceiveIndexesByAddressType["nativeSegwit"])
        assertEquals(mapOf("nativeSegwit" to 2), sut.backupSnapshot().getOrThrow())
    }

    @Test
    fun `public address rotation checks preserve reservations and propagate failures`() = test {
        assertFalse(sut.needsPublicAddressRotation(CONTACT_KEY).getOrThrow())
        verify(lightningRepo, never()).newAddressInfoForType(any())
        whenever(coreService.isAddressUsed(any())).thenReturn(false)
        whenever(lightningRepo.newAddressInfoForType(AddressType.P2WPKH)).thenReturn(
            Result.success(AddressDerivationInfo("bcrt1qpublic", 1)),
        )
        whenever(lightningRepo.revealReceiveAddresses(any(), any())).thenReturn(Result.success(Unit))
        whenever(lightningRepo.getAddressBalance(any())).thenReturn(Result.success(0u))
        assertEquals("bcrt1qpublic", sut.currentOrRotatedPublicAddress(CONTACT_KEY).getOrThrow())
        val before = reservationData.value
        val backupVersion = sut.backupStateVersion.value
        assertFalse(sut.needsPublicAddressRotation(CONTACT_KEY).getOrThrow())
        whenever(coreService.isAddressUsed("bcrt1qpublic")).thenReturn(true)
        assertTrue(sut.needsPublicAddressRotation(CONTACT_KEY).getOrThrow())
        whenever(coreService.isAddressUsed("bcrt1qpublic")).thenReturn(false)
        whenever(lightningRepo.getAddressBalance("bcrt1qpublic")).thenReturn(Result.success(1u))
        assertTrue(sut.needsPublicAddressRotation(CONTACT_KEY).getOrThrow())
        whenever(coreService.isAddressUsed("bcrt1qpublic"))
            .doSuspendableAnswer { throw PrivatePaykitAddressReservationError.AddressReservationFailed }
        assertTrue(sut.needsPublicAddressRotation(CONTACT_KEY).isFailure)
        assertEquals(before, reservationData.value)
        assertEquals(backupVersion, sut.backupStateVersion.value)
        verify(lightningRepo, times(1)).newAddressInfoForType(any())
    }

    @Test
    fun `public address acknowledgement holds its match through rotation and clear`() = test {
        whenever(lightningRepo.newAddressInfoForType(AddressType.P2WPKH)).thenReturn(
            Result.success(AddressDerivationInfo("bcrt1qpublic", 1)),
            Result.success(AddressDerivationInfo("bcrt1qrotated", 2)),
        )
        whenever(lightningRepo.revealReceiveAddresses(any(), any())).thenReturn(Result.success(Unit))
        whenever(lightningRepo.getAddressBalance(any())).thenReturn(Result.success(0u))
        whenever(reservationStore.reset()).thenAnswer { reservationData.value = PrivatePaykitReservationData() }
        assertEquals("bcrt1qpublic", sut.currentOrRotatedPublicAddress(CONTACT_KEY).getOrThrow())

        for (clear in listOf(false, true)) {
            val address = if (clear) "bcrt1qrotated" else "bcrt1qpublic"
            val before = reservationData.value
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            doSuspendableAnswer {
                entered.complete(Unit)
                release.await()
                settingsData.update(it.getArgument<(SettingsData) -> SettingsData>(0))
            }.whenever(settingsStore).update(any())
            val acknowledgement = async {
                sut.withPublicAddressMatch(CONTACT_KEY.removePrefix("pubky"), address) { matches ->
                    assertTrue(matches)
                    settingsStore.update { it.copy(publicPaykitCleanupPending = !matches) }
                }
            }
            entered.await()
            whenever(coreService.isAddressUsed(address)).thenReturn(true)
            val mutation = async {
                if (clear) {
                    sut.clear()
                } else {
                    assertEquals("bcrt1qrotated", sut.currentOrRotatedPublicAddress(CONTACT_KEY).getOrThrow())
                }
            }
            assertFalse(mutation.isCompleted)
            assertEquals(before, reservationData.value)
            release.complete(Unit)
            acknowledgement.await()
            mutation.await()

            assertFalse(settingsData.value.publicPaykitCleanupPending)
            sut.withPublicAddressMatch(CONTACT_KEY, address) { assertFalse(it) }
            assertEquals(
                if (clear) null else "bcrt1qrotated",
                reservationData.value.publicAssignments[CONTACT_KEY]?.address,
            )
        }
        verify(lightningRepo, never()).addressInfoForType(any(), any())
    }

    @Test
    fun `public assignments are scoped to profile identity`() = test {
        val otherIdentity = "pubky4rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        whenever(lightningRepo.newAddressInfoForType(AddressType.P2WPKH)).thenReturn(
            Result.success(AddressDerivationInfo("bcrt1qfirst", 1)),
            Result.success(AddressDerivationInfo("bcrt1qsecond", 2)),
        )
        whenever(lightningRepo.revealReceiveAddresses(any(), any())).thenReturn(Result.success(Unit))
        sut.currentOrRotatedPublicAddress(CONTACT_KEY.removePrefix("pubky")).getOrThrow()
        sut.currentOrRotatedPublicAddress(otherIdentity).getOrThrow()
        assertEquals(setOf(CONTACT_KEY, otherIdentity), reservationData.value.publicAssignments.keys)
        assertTrue(reservationData.value.contactAssignments.isEmpty())
    }

    @Test
    fun `failed reservation write cannot become a reusable public assignment`() = test {
        whenever(lightningRepo.newAddressInfoForType(AddressType.P2WPKH)).thenReturn(
            Result.success(AddressDerivationInfo("bcrt1qfailed", 1)),
            Result.success(AddressDerivationInfo("bcrt1qdurable", 2)),
        )
        var failWrite = true
        whenever(reservationStore.update(any())).thenAnswer {
            if (failWrite) error("disk unavailable")
            val transform = it.getArgument<(PrivatePaykitReservationData) -> PrivatePaykitReservationData>(0)
            reservationData.update(transform)
        }
        assertTrue(sut.currentOrRotatedPublicAddress(CONTACT_KEY).isFailure)
        assertTrue(reservationData.value.publicAssignments.isEmpty())
        failWrite = false
        assertEquals("bcrt1qdurable", sut.currentOrRotatedPublicAddress(CONTACT_KEY).getOrThrow())
        assertEquals("bcrt1qdurable", reservationData.value.publicAssignments[CONTACT_KEY]?.address)
    }

    @Test
    fun `concurrent public allocation waits for durable persistence and reuses the same assignment`() = test {
        whenever(coreService.isAddressUsed(any())).thenReturn(false)
        val writing = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        whenever(lightningRepo.newAddressInfoForType(AddressType.P2WPKH)).thenReturn(
            Result.success(AddressDerivationInfo("bcrt1qpublic", 1)),
        )
        whenever(lightningRepo.getAddressBalance(any())).thenReturn(Result.success(0u))
        whenever(reservationStore.update(any())).doSuspendableAnswer {
            writing.complete(Unit)
            resume.await()
            val transform = it.getArgument<(PrivatePaykitReservationData) -> PrivatePaykitReservationData>(0)
            reservationData.update(transform)
        }
        val first = async { sut.currentOrRotatedPublicAddress(CONTACT_KEY) }
        writing.await()
        val second = async { sut.currentOrRotatedPublicAddress(CONTACT_KEY) }
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        assertTrue(reservationData.value.publicAssignments.isEmpty())
        resume.complete(Unit)
        assertEquals("bcrt1qpublic", first.await().getOrThrow())
        assertEquals("bcrt1qpublic", second.await().getOrThrow())
        verify(lightningRepo, times(1)).newAddressInfoForType(AddressType.P2WPKH)
    }

    @Test
    fun `legacy public address remains quarantined through backup and becomes an indexed reservation`() = test {
        settingsData.value = SettingsData(legacyPublicAddressQuarantinePending = true)
        cacheData.value = AppCacheData(onchainAddress = PRIVATE_ADDRESS)
        assertTrue(sut.isUnavailableForReusableReceive(PRIVATE_ADDRESS))
        assertFalse(settingsData.value.legacyPublicAddressQuarantinePending)
        val legacy = sut.quarantinedPublicAddressesSnapshot()
        assertEquals(setOf(PRIVATE_ADDRESS), legacy)
        sut.restoreBackup(mapOf("nativeSegwit" to 4), legacy).getOrThrow()
        whenever(lightningRepo.revealReceiveAddresses(any(), any())).thenReturn(Result.success(Unit))
        whenever(lightningRepo.newAddressInfoForType(AddressType.P2WPKH)).thenReturn(
            Result.success(AddressDerivationInfo(PRIVATE_ADDRESS, 5)),
            Result.success(AddressDerivationInfo("bcrt1qsafe", 6)),
        )
        assertEquals("bcrt1qsafe", sut.nextReusableReceiveAddress().getOrThrow())
        assertEquals(setOf(5), reservationData.value.reservedReceiveIndexesByAddressType["nativeSegwit"])
        assertEquals(mapOf("nativeSegwit" to 5), sut.backupSnapshot().getOrThrow())
        assertEquals(legacy, sut.quarantinedPublicAddressesSnapshot())
    }

    @Test
    fun `restoring an older backup cannot lower reservation floors or reuse public assignments`() = test {
        reservationData.value = PrivatePaykitReservationData(
            reservedReceiveIndexesByAddressType = mapOf("nativeSegwit" to setOf(10)),
            publicAssignments = mapOf(
                CONTACT_KEY to PrivatePaykitStoredAssignmentData("nativeSegwit", 10, PRIVATE_ADDRESS),
            ),
        )
        sut.restoreBackup(mapOf("nativeSegwit" to 2)).getOrThrow()
        assertTrue(reservationData.value.publicAssignments.isEmpty())
        assertEquals(mapOf("nativeSegwit" to 10), sut.backupSnapshot().getOrThrow())
        whenever(lightningRepo.revealReceiveAddresses(10, AddressType.P2WPKH)).thenReturn(Result.success(Unit))
        whenever(lightningRepo.newAddressInfoForType(AddressType.P2WPKH)).thenReturn(
            Result.success(AddressDerivationInfo("bcrt1qfresh", 11)),
        )
        assertEquals("bcrt1qfresh", sut.currentOrRotatedPublicAddress(CONTACT_KEY).getOrThrow())
    }

    @Test
    fun `failed legacy quarantine persistence leaves migration pending`() = test {
        settingsData.value = SettingsData(legacyPublicAddressQuarantinePending = true)
        cacheData.value = AppCacheData(onchainAddress = PRIVATE_ADDRESS)
        whenever(reservationStore.update(any())).thenThrow(IllegalStateException("disk"))
        assertFailsWith<IllegalStateException> { sut.isUnavailableForReusableReceive(PRIVATE_ADDRESS) }
        assertTrue(settingsData.value.legacyPublicAddressQuarantinePending)
        assertTrue(reservationData.value.quarantinedPublicAddresses.isEmpty())
        verify(lightningRepo, never()).newAddressInfoForType(any())
    }

    @Test
    fun `pending exposure blocks cached and allocated addresses until quarantine is durable`() = test {
        val assignment = PrivatePaykitStoredAssignmentData("nativeSegwit", 1, PRIVATE_ADDRESS)
        reservationData.update {
            it.copy(
                publicAssignments = mapOf(CONTACT_KEY to assignment),
                contactAssignments = mapOf(CONTACT_KEY to assignment),
            )
        }
        val key = Keychain.Key.PAYKIT_PENDING_PUBLIC_ADDRESS_RESTORE.name
        val reading = """{"type":"reading"}"""
        val source = """{"type":"source","fingerprint":"${"0".repeat(64)}"}"""
        val address = """{"type":"address","address":"$PRIVATE_ADDRESS"}"""
        for (pending in listOf(reading, source, address)) {
            keychain.upsertString(key, pending)
            assertTrue(sut.nextReusableReceiveAddress().isFailure)
            assertTrue(sut.currentOrRotatedAddress(CONTACT_KEY).isFailure)
            assertTrue(sut.currentOrRotatedPublicAddress(CONTACT_KEY).isFailure)
            assertFailsWith<PrivatePaykitAddressReservationError.PublicAddressRestorePending> {
                sut.isUnavailableForReusableReceive(PRIVATE_ADDRESS)
            }
            var publicAddressMatches = true
            sut.withPublicAddressMatch(CONTACT_KEY, PRIVATE_ADDRESS) { publicAddressMatches = it }
            assertFalse(publicAddressMatches)
            assertTrue(sut.backupSnapshot().isFailure)
            assertTrue(sut.restoreBackup(null).isFailure)
            assertEquals(pending, keychain.loadString(key))
        }
        verify(lightningRepo, never()).newAddressInfoForType(any())
        verify(reservationStore, never()).update(any())

        assertFalse(sut.preparePublicAddressRestore())
        assertEquals(setOf(PRIVATE_ADDRESS), reservationData.value.quarantinedPublicAddresses)
        assertTrue(cacheData.value.backupStatuses.getValue(BackupCategory.WALLET).isRequired)
        assertTrue(cacheData.value.backupStatuses.getValue(BackupCategory.METADATA).isRequired)
        assertEquals(reading, keychain.loadString(key))
        sut.finishPublicAddressRestoreWithoutMetadata()
        assertNull(keychain.loadString(key))
        assertTrue(sut.isUnavailableForReusableReceive(PRIVATE_ADDRESS))
    }

    @Test
    fun `malformed public address recovery records remain blocked and unchanged`() = test {
        val key = Keychain.Key.PAYKIT_PENDING_PUBLIC_ADDRESS_RESTORE.name
        val records = listOf(
            "",
            PRIVATE_ADDRESS,
            "{",
            """{"type":"unknown"}""",
            """{"type":"reading","address":"$PRIVATE_ADDRESS"}""",
            """{"type":"source","fingerprint":""}""",
            """{"type":"source"}""",
            """{"type":"address","address":""}""",
        )
        for (record in records) {
            keychain.upsertString(key, record)
            assertFailsWith<Exception> { sut.preparePublicAddressRestore() }
            assertFailsWith<Exception> { sut.finishPublicAddressRestoreWithoutMetadata() }
            assertEquals(record, keychain.loadString(key))
            assertTrue(sut.nextReusableReceiveAddress().isFailure)
            assertTrue(sut.backupSnapshot().isFailure)
        }
        verify(reservationStore, never()).update(any())
        verify(lightningRepo, never()).newAddressInfoForType(any())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `exposure recovery waits for an admitted allocation before blocking new allocations`() = test {
        val allocationStarted = CompletableDeferred<Unit>()
        val finishAllocation = CompletableDeferred<Unit>()
        whenever(lightningRepo.newAddressInfoForType(any())).doSuspendableAnswer {
            allocationStarted.complete(Unit)
            finishAllocation.await()
            Result.success(AddressDerivationInfo("bcrt1qreceive", 0))
        }
        val allocation = async { sut.nextReusableReceiveAddress() }
        allocationStarted.await()
        val recovery = async { sut.preparePublicAddressRestore() }
        runCurrent()
        assertFalse(recovery.isCompleted)
        assertFalse(keychain.exists(Keychain.Key.PAYKIT_PENDING_PUBLIC_ADDRESS_RESTORE.name))

        finishAllocation.complete(Unit)
        assertEquals("bcrt1qreceive", allocation.await().getOrThrow())
        assertFalse(recovery.await())
        assertTrue(keychain.exists(Keychain.Key.PAYKIT_PENDING_PUBLIC_ADDRESS_RESTORE.name))
        assertTrue(sut.nextReusableReceiveAddress().isFailure)
        verify(lightningRepo, times(1)).newAddressInfoForType(any())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `contact cleanup rechecks snapshot after ledger load and lock handoff`() = test {
        for (clearUnlisted in listOf(false, true)) {
            val assignment = PrivatePaykitStoredAssignmentData("nativeSegwit", 1, PRIVATE_ADDRESS)
            val initial = PrivatePaykitReservationData(
                contactAssignments = mapOf(CONTACT_KEY to assignment),
                contactAssignmentHistory = mapOf(CONTACT_KEY to listOf(assignment)),
            )
            reservationData.update { initial }
            val loadStarted = CompletableDeferred<Unit>()
            val resumeLoad = CompletableDeferred<Unit>()
            whenever(reservationStore.data).thenReturn(
                flow {
                    loadStarted.complete(Unit)
                    resumeLoad.await()
                    emit(reservationData.value)
                },
            )
            sut = PrivatePaykitAddressReservationRepo(
                testDispatcher, reservationStore, settingsStore, coreService, lightningRepo, cacheStore, keychain,
            )
            var isCurrent = true
            val loading = async { sut.backupSnapshot().getOrThrow() }
            loadStarted.await()
            val cleanup = async {
                if (clearUnlisted) {
                    sut.clearContactAssignments(emptyList()) { isCurrent }
                } else {
                    sut.removeContactAssignments(listOf(CONTACT_KEY)) { isCurrent }
                }
            }
            runCurrent()
            assertFalse(cleanup.isCompleted)
            isCurrent = false
            resumeLoad.complete(Unit)
            loading.await()
            cleanup.await()

            assertEquals(initial, reservationData.value)
            if (clearUnlisted) {
                sut.clearContactAssignments(emptyList()) { true }
            } else {
                sut.removeContactAssignments(listOf(CONTACT_KEY)) { true }
            }
            assertTrue(reservationData.value.contactAssignments.isEmpty())
            assertTrue(reservationData.value.contactAssignmentHistory.isEmpty())
        }
    }

    @Test
    fun `admitted assignment removal finishes when snapshot changes during persistence`() = test {
        val assignment = PrivatePaykitStoredAssignmentData("nativeSegwit", 1, PRIVATE_ADDRESS)
        reservationData.update {
            PrivatePaykitReservationData(contactAssignments = mapOf(CONTACT_KEY to assignment))
        }
        val writeStarted = CompletableDeferred<Unit>()
        val resumeWrite = CompletableDeferred<Unit>()
        var isCurrent = true
        whenever(reservationStore.update(any())).doSuspendableAnswer {
            val transform = it.getArgument<(PrivatePaykitReservationData) -> PrivatePaykitReservationData>(0)
            writeStarted.complete(Unit)
            resumeWrite.await()
            reservationData.update(transform)
        }
        val cleanup = async { sut.removeContactAssignments(listOf(CONTACT_KEY)) { isCurrent } }
        writeStarted.await()
        isCurrent = false
        resumeWrite.complete(Unit)
        cleanup.await()

        assertTrue(reservationData.value.contactAssignments.isEmpty())
    }

    @Test
    fun `contactsWithUsedReservedAddresses treats positive ldk address balance as used`() = test {
        reservationData.value = PrivatePaykitReservationData(
            reservedReceiveIndexesByAddressType = mapOf("nativeSegwit" to setOf(1)),
            contactAssignments = mapOf(
                CONTACT_KEY to PrivatePaykitStoredAssignmentData(
                    addressType = "nativeSegwit",
                    receiveIndex = 1,
                    address = PRIVATE_ADDRESS,
                ),
            ),
        )
        whenever(coreService.getAddressUsage(listOf(PRIVATE_ADDRESS)))
            .thenReturn(mapOf(PRIVATE_ADDRESS to Result.success(false)))
        whenever(lightningRepo.getAddressBalance(PRIVATE_ADDRESS)).thenReturn(Result.success(100_000u))

        val result = sut.contactsWithUsedReservedAddresses()

        assertEquals(listOf(CONTACT_KEY), result)
    }

    @Test
    fun `contact usage checks share transaction history and preserve activity and ldk matches`() = test {
        val addresses = listOf("activity-address", "secondary-address", "unused-address", "ldk-address")
        val keys = addresses.indices.map { "contact-$it" }
        reservationData.value = PrivatePaykitReservationData(
            contactAssignments = keys.zip(addresses).mapIndexed { index, (key, address) ->
                key to PrivatePaykitStoredAssignmentData("nativeSegwit", index, address)
            }.toMap(),
        )
        whenever(coreService.isAddressUsed(any())).thenCallRealMethod()
        whenever(coreService.getAddressUsage(any())).thenCallRealMethod()
        whenever(lightningRepo.getAddressBalance(any())).thenReturn(Result.success(0u))
        whenever(lightningRepo.getAddressBalance("ldk-address")).thenReturn(Result.success(1u))
        val binding = Class.forName("com.synonym.bitkitcore.Bitkitcore_androidKt")
        val activityUsage = binding.getMethod("isAddressUsed", String::class.java)
        val transactionHistory = binding.getMethod("getAllTransactionDetails")
        val details = TransactionDetails(
            "wallet0",
            "batch-payment",
            2,
            emptyList(),
            listOf("receive-address", "secondary-address").map { address ->
                mock<TxOutput> { on { scriptpubkeyAddress }.thenReturn(address) }
            },
        )
        var historyReads = 0
        var historyUnavailable = false
        var transactions = listOf(details)
        ServiceQueue.CORE.background {
            mockStatic(binding).use { native ->
                addresses.forEach { address ->
                    native.`when`<Boolean> { activityUsage.invoke(null, address) }
                        .thenReturn(address == "activity-address")
                }
                native.`when`<List<TransactionDetails>> { transactionHistory.invoke(null) }.thenAnswer {
                    historyReads++
                    check(!historyUnavailable) { "Transaction history unavailable" }
                    transactions
                }

                assertEquals(listOf(keys[0], keys[1], keys[3]), sut.contactsWithUsedReservedAddresses())
                assertEquals(1, historyReads)
                transactions = emptyList()
                assertEquals(listOf(keys[0], keys[3]), sut.contactsWithUsedReservedAddresses())
                assertEquals(2, historyReads)
                historyUnavailable = true
                assertEquals(listOf(keys[0]), sut.contactsWithUsedReservedAddresses())
                assertEquals(3, historyReads)
            }
        }
        verify(lightningRepo, never()).getAddressBalance("activity-address")
        verify(lightningRepo, times(1)).getAddressBalance("secondary-address")
        verify(lightningRepo, times(2)).getAddressBalance("ldk-address")
    }

    @Test
    fun `backupSnapshot keeps highest active and restored private indexes`() = test {
        reservationData.value = PrivatePaykitReservationData(
            reservedReceiveIndexesByAddressType = mapOf("nativeSegwit" to setOf(1, 4)),
            restoredReservedReceiveIndexCeilingsByAddressType = mapOf(
                "nativeSegwit" to 3,
                "taproot" to 2,
            ),
        )

        val result = sut.backupSnapshot().getOrThrow()

        assertEquals(
            mapOf(
                "nativeSegwit" to 4,
                "taproot" to 2,
            ),
            result,
        )
    }

    @Test
    fun `restoreBackup preserves private index ceilings and clears assignments`() = test {
        reservationData.value = PrivatePaykitReservationData(
            reservedReceiveIndexesByAddressType = mapOf("nativeSegwit" to setOf(1)),
            contactAssignments = mapOf(
                CONTACT_KEY to PrivatePaykitStoredAssignmentData(
                    addressType = "nativeSegwit",
                    receiveIndex = 1,
                    address = PRIVATE_ADDRESS,
                ),
            ),
        )

        sut.restoreBackup(mapOf("nativeSegwit" to 2)).getOrThrow()

        assertEquals(
            PrivatePaykitReservationData(
                restoredReservedReceiveIndexCeilingsByAddressType = mapOf("nativeSegwit" to 2),
            ),
            reservationData.value,
        )
    }

    @Test
    fun `nextReusableReceiveAddress skips restored private receive indexes`() = test {
        reservationData.value = PrivatePaykitReservationData(
            restoredReservedReceiveIndexCeilingsByAddressType = mapOf("nativeSegwit" to 2),
        )
        whenever(lightningRepo.revealReceiveAddresses(2, AddressType.P2WPKH)).thenReturn(Result.success(Unit))
        whenever(lightningRepo.newAddressInfoForType(AddressType.P2WPKH)).thenReturn(
            Result.success(AddressDerivationInfo(address = "address3", index = 3)),
        )

        val result = sut.nextReusableReceiveAddress(AddressType.P2WPKH).getOrThrow()

        assertEquals("address3", result)
        verify(lightningRepo).revealReceiveAddresses(2, AddressType.P2WPKH)
    }

    @Test
    fun `nextReusableReceiveAddress advances past high restored private receive ceiling`() = test {
        reservationData.value = PrivatePaykitReservationData(
            restoredReservedReceiveIndexCeilingsByAddressType = mapOf("nativeSegwit" to 505),
        )
        whenever(lightningRepo.revealReceiveAddresses(505, AddressType.P2WPKH)).thenReturn(Result.success(Unit))
        whenever(lightningRepo.newAddressInfoForType(AddressType.P2WPKH)).thenReturn(
            Result.success(AddressDerivationInfo(address = "address506", index = 506)),
        )

        val result = sut.nextReusableReceiveAddress(AddressType.P2WPKH).getOrThrow()

        assertEquals("address506", result)
        verify(lightningRepo).revealReceiveAddresses(505, AddressType.P2WPKH)
    }

    @Test
    fun `isUnavailableForReusableReceive does not scan restored private receive ceilings by address`() = test {
        reservationData.value = PrivatePaykitReservationData(
            restoredReservedReceiveIndexCeilingsByAddressType = mapOf("nativeSegwit" to 505),
        )

        val result = sut.isUnavailableForReusableReceive(PRIVATE_ADDRESS)

        assertFalse(result)
        verify(lightningRepo, never()).addressInfosForType(any(), any(), any(), any())
    }

    @Test
    fun `removeContactAssignments removes selected attribution and preserves unrelated reservations`() = test {
        val historyOnlyKey = "pubky4rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        val savedKey = "pubky1rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        val assignment = PrivatePaykitStoredAssignmentData("nativeSegwit", 1, PRIVATE_ADDRESS)
        val oldAssignment = assignment.copy(receiveIndex = 2, address = "bcrt1qold")
        val historyOnlyAssignment = assignment.copy(receiveIndex = 3, address = "bcrt1qhistory")
        val savedAssignment = assignment.copy(receiveIndex = 4, address = "bcrt1qsaved")
        val savedHistory = assignment.copy(receiveIndex = 5, address = "bcrt1qsavedhistory")
        val original = PrivatePaykitReservationData(
            reservedReceiveIndexesByAddressType = mapOf("nativeSegwit" to setOf(1, 2, 3, 4, 5)),
            contactAssignments = mapOf(CONTACT_KEY to assignment, savedKey to savedAssignment),
            contactAssignmentHistory = mapOf(
                CONTACT_KEY to listOf(oldAssignment),
                historyOnlyKey to listOf(historyOnlyAssignment),
                savedKey to listOf(savedHistory),
            ),
            restoredReservedReceiveIndexCeilingsByAddressType = mapOf("taproot" to 6),
        )
        reservationData.value = original
        whenever(lightningRepo.addressInfoForType(AddressType.P2WPKH, 1)).thenReturn(
            Result.success(AddressDerivationInfo(address = PRIVATE_ADDRESS, index = 1)),
        )

        sut.removeContactAssignments(listOf(CONTACT_KEY.removePrefix("pubky"), historyOnlyKey))

        assertEquals(
            original.copy(
                contactAssignments = mapOf(savedKey to savedAssignment),
                contactAssignmentHistory = mapOf(savedKey to listOf(savedHistory)),
            ),
            reservationData.value,
        )
        listOf(assignment, oldAssignment, historyOnlyAssignment).forEach {
            assertNull(sut.contactPublicKeyForReservedAddress(it.address))
            assertNull(sut.currentContactPublicKeyForReservedAddress(it.address))
        }
        assertEquals(savedKey, sut.currentContactPublicKeyForReservedAddress(savedAssignment.address))
        assertEquals(savedKey, sut.contactPublicKeyForReservedAddress(savedHistory.address))
        assertTrue(sut.isUnavailableForReusableReceive(PRIVATE_ADDRESS))
        verify(reservationStore).update(any())
    }

    @Test
    fun `removeContactAssignments invalidates attribution even if persistence fails`() = test {
        val historyOnlyKey = "pubky4rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        val historicalAddress = "bcrt1qhistory"
        reservationData.value = PrivatePaykitReservationData(
            contactAssignments = mapOf(
                CONTACT_KEY to PrivatePaykitStoredAssignmentData(
                    addressType = "nativeSegwit",
                    receiveIndex = 1,
                    address = PRIVATE_ADDRESS,
                ),
            ),
            contactAssignmentHistory = mapOf(
                CONTACT_KEY to listOf(
                    PrivatePaykitStoredAssignmentData(
                        addressType = "nativeSegwit",
                        receiveIndex = 1,
                        address = PRIVATE_ADDRESS,
                    ),
                ),
                historyOnlyKey to listOf(
                    PrivatePaykitStoredAssignmentData("nativeSegwit", 2, historicalAddress),
                ),
            ),
        )

        assertEquals(CONTACT_KEY, sut.contactPublicKeyForReservedAddress(PRIVATE_ADDRESS))
        assertEquals(historyOnlyKey, sut.contactPublicKeyForReservedAddress(historicalAddress))
        val persisted = reservationData.value
        val version = sut.attributionVersion
        whenever(reservationStore.update(any())).thenThrow(IllegalStateException("disk"))
        assertFailsWith<IllegalStateException> {
            sut.removeContactAssignments(listOf(CONTACT_KEY, historyOnlyKey))
        }

        assertTrue(sut.attributionVersion > version)
        assertNull(sut.contactPublicKeyForReservedAddress(PRIVATE_ADDRESS))
        assertNull(sut.currentContactPublicKeyForReservedAddress(PRIVATE_ADDRESS))
        assertNull(sut.contactPublicKeyForReservedAddress(historicalAddress))
        assertEquals(persisted, reservationData.value)

        org.mockito.kotlin.doAnswer {
            val transform = it.getArgument<(PrivatePaykitReservationData) -> PrivatePaykitReservationData>(0)
            reservationData.value = transform(reservationData.value)
        }.whenever(reservationStore).update(any())
        sut.removeContactAssignments(listOf(CONTACT_KEY, historyOnlyKey))
        assertTrue(reservationData.value.contactAssignments.isEmpty())
        assertTrue(reservationData.value.contactAssignmentHistory.isEmpty())
    }

    @Test
    fun `reservation derivation failure propagates and remains retryable`() = test {
        reservationData.value = PrivatePaykitReservationData(
            contactAssignments = mapOf(
                CONTACT_KEY to PrivatePaykitStoredAssignmentData(addressType = "nativeSegwit", receiveIndex = 1),
            ),
        )
        whenever(lightningRepo.addressInfoForType(any(), any())).thenReturn(
            Result.failure(IllegalStateException("unavailable")),
            Result.success(AddressDerivationInfo(address = PRIVATE_ADDRESS, index = 1)),
        )

        assertFailsWith<IllegalStateException> { sut.contactPublicKeyForReservedAddress(PRIVATE_ADDRESS) }
        assertEquals(CONTACT_KEY, sut.contactPublicKeyForReservedAddress(PRIVATE_ADDRESS))
    }

    @Test
    fun `contactPublicKeyForReservedAddress skips assignments for other address types`() = test {
        reservationData.value = PrivatePaykitReservationData(
            contactAssignments = mapOf(
                CONTACT_KEY to PrivatePaykitStoredAssignmentData(
                    addressType = "taproot",
                    receiveIndex = 1,
                    address = "",
                ),
            ),
        )

        assertNull(sut.contactPublicKeyForReservedAddress(PRIVATE_ADDRESS))
        verify(lightningRepo, never()).addressInfoForType(any(), any())
    }

    @Test
    fun `clearContactAssignments removes stale private address attribution history`() = test {
        val savedContactKey = "pubky1rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        val savedPrivateAddress = "bcrt1qsavedweva9vextackckt6pjy0mmuc54gnn8peu"
        reservationData.value = PrivatePaykitReservationData(
            contactAssignments = mapOf(
                CONTACT_KEY to PrivatePaykitStoredAssignmentData(
                    addressType = "nativeSegwit",
                    receiveIndex = 1,
                    address = PRIVATE_ADDRESS,
                ),
                savedContactKey to PrivatePaykitStoredAssignmentData(
                    addressType = "nativeSegwit",
                    receiveIndex = 2,
                    address = savedPrivateAddress,
                ),
            ),
            contactAssignmentHistory = mapOf(
                CONTACT_KEY to listOf(
                    PrivatePaykitStoredAssignmentData(
                        addressType = "nativeSegwit",
                        receiveIndex = 1,
                        address = PRIVATE_ADDRESS,
                    ),
                ),
                savedContactKey to listOf(
                    PrivatePaykitStoredAssignmentData(
                        addressType = "nativeSegwit",
                        receiveIndex = 2,
                        address = savedPrivateAddress,
                    ),
                ),
            ),
        )

        sut.clearContactAssignments(excludingPublicKeys = listOf(savedContactKey))

        assertNull(sut.contactPublicKeyForReservedAddress(PRIVATE_ADDRESS))
        assertEquals(savedContactKey, sut.contactPublicKeyForReservedAddress(savedPrivateAddress))
    }
}
