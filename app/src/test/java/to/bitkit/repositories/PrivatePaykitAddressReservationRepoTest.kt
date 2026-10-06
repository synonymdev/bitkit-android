package to.bitkit.repositories

import com.synonym.bitkitcore.AddressType
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.PrivatePaykitReservationData
import to.bitkit.data.PrivatePaykitReservationStore
import to.bitkit.data.PrivatePaykitStoredAssignmentData
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
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
        whenever(lightningRepo.lightningState).thenReturn(lightningState)

        sut = PrivatePaykitAddressReservationRepo(
            ioDispatcher = testDispatcher,
            reservationStore = reservationStore,
            settingsStore = settingsStore,
            coreService = coreService,
            lightningRepo = lightningRepo,
        )
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
        whenever(coreService.isAddressUsed(PRIVATE_ADDRESS)).thenReturn(false)
        whenever(lightningRepo.getAddressBalance(PRIVATE_ADDRESS)).thenReturn(Result.success(100_000u))

        val result = sut.contactsWithUsedReservedAddresses()

        assertEquals(listOf(CONTACT_KEY), result)
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
