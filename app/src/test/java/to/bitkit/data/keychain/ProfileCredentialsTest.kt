package to.bitkit.data.keychain

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import to.bitkit.test.InMemoryDataStore
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileCredentialsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val session = stringPreferencesKey("PAYKIT_SESSION_0")
    private val secret = stringPreferencesKey("PUBKY_SECRET_KEY_0")
    private val sharedSource = stringPreferencesKey("SHARED_PUBKY_SOURCE_0")
    private val mnemonic = stringPreferencesKey("BIP39_MNEMONIC_0")
    private val otherWallet = stringPreferencesKey("PAYKIT_SESSION_1")
    private var walletIndex = 0L
    private var admitted = true

    private fun requireCurrent() = check(admitted) { "Original profile admission ended" }

    private fun credentials(store: DataStore<Preferences>) = ProfileCredentialStore(
        store,
        walletIndex = { walletIndex },
        decrypt = { value ->
            if (value == "unreadable") throw IOException("Key unavailable")
            value.substringAfter(':').toByteArray()
        },
    )

    private fun initial() = mutablePreferencesOf(
        session to "first:session",
        secret to "first:managed-key",
        sharedSource to "first:ring-reference",
        mnemonic to "unchanged-wallet",
        otherWallet to "other-wallet-session",
    )

    @Test
    fun `local confirmation can recover an unavailable read without restoring ordinary permission`() = runTest {
        val backing = InMemoryDataStore<Preferences>(initial())
        var unavailable = false
        val store = object : DataStore<Preferences> {
            override val data = backing.data.map {
                if (unavailable) throw IOException("Read unavailable")
                it
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (unavailable) throw IOException("Read unavailable")
                return backing.updateData(transform)
            }
        }
        val credentials = credentials(store)
        val target = credentials.capture(::requireCurrent)
        unavailable = true
        assertFailsWith<IOException> { credentials.validate(target, ::requireCurrent) }
        assertFailsWith<IOException> { credentials.eraseAfterLocalConfirmation(target, ::requireCurrent) }
        assertEquals(initial(), backing.data.first())
        unavailable = false
        assertFailsWith<ProfileCredentialsChanged> { credentials.erase(target, ::requireCurrent) }
        credentials.eraseAfterLocalConfirmation(target, ::requireCurrent)
        assertEquals(
            mutablePreferencesOf(mnemonic to "unchanged-wallet", otherWallet to "other-wallet-session").asMap(),
            backing.data.first().asMap(),
        )
        assertFailsWith<ProfileCredentialsChanged> { credentials.validate(target, ::requireCurrent) }
    }

    @Test
    fun `local confirmation compares original values again after decryption recovers`() = runTest {
        val store = InMemoryDataStore<Preferences>(initial())
        val credentials = credentials(store)
        val target = credentials.capture(::requireCurrent)
        store.edit { it[secret] = "unreadable" }
        assertFailsWith<IOException> { credentials.validate(target, ::requireCurrent) }
        assertFailsWith<IOException> { credentials.eraseAfterLocalConfirmation(target, ::requireCurrent) }
        assertEquals("first:session", store.data.first()[session])
        store.updateData { initial() }
        credentials.eraseAfterLocalConfirmation(target, ::requireCurrent)
        assertFalse(store.data.first().contains(secret))
        assertEquals("unchanged-wallet", store.data.first()[mnemonic])
        assertFailsWith<ProfileCredentialsChanged> { credentials.validate(target, ::requireCurrent) }
    }

    @Test
    fun `local recovery rejects changed or absent entries and never accepts their restoration`() = runTest {
        listOf(session, secret, sharedSource).forEach { key ->
            listOf("replacement", null).forEach { replacement ->
                val store = InMemoryDataStore<Preferences>(initial())
                val credentials = credentials(store)
                val target = credentials.capture(::requireCurrent)
                store.edit { it[sharedSource] = "unreadable" }
                assertFailsWith<IOException> { credentials.validate(target, ::requireCurrent) }
                store.updateData { initial() }
                store.edit {
                    if (replacement == null) it.remove(key) else it[key] = replacement
                }
                val changed = store.data.first()
                assertFailsWith<ProfileCredentialsChanged> {
                    credentials.eraseAfterLocalConfirmation(target, ::requireCurrent)
                }
                assertEquals(changed, store.data.first())
                store.updateData { initial() }
                assertFailsWith<ProfileCredentialsChanged> {
                    credentials.eraseAfterLocalConfirmation(target, ::requireCurrent)
                }
                assertEquals(initial(), store.data.first())
            }
        }
    }

    @Test
    fun `local recovery cannot adopt an added originally absent key`() = runTest {
        val original = initial().also { it.remove(secret) }
        val store = InMemoryDataStore<Preferences>(original)
        val credentials = credentials(store)
        val target = credentials.capture(::requireCurrent)
        store.edit { it[sharedSource] = "unreadable" }
        assertFailsWith<IOException> { credentials.validate(target, ::requireCurrent) }
        store.updateData { original }
        store.edit { it[secret] = "new-key" }
        val changed = store.data.first()
        assertFailsWith<ProfileCredentialsChanged> {
            credentials.eraseAfterLocalConfirmation(target, ::requireCurrent)
        }
        assertEquals(changed, store.data.first())
        store.updateData { original }
        assertFailsWith<ProfileCredentialsChanged> {
            credentials.eraseAfterLocalConfirmation(target, ::requireCurrent)
        }
    }

    @Test
    fun `an observed mismatch remains terminal even when another entry is unreadable`() = runTest {
        val store = InMemoryDataStore<Preferences>(initial())
        val credentials = credentials(store)
        val target = credentials.capture(::requireCurrent)
        store.edit {
            it[session] = "replacement"
            it[secret] = "unreadable"
        }
        assertFailsWith<ProfileCredentialsChanged> { credentials.validate(target, ::requireCurrent) }
        store.updateData { initial() }
        assertFailsWith<ProfileCredentialsChanged> {
            credentials.eraseAfterLocalConfirmation(target, ::requireCurrent)
        }
        assertEquals(initial(), store.data.first())
    }

    @Test
    fun `local recovery rejects store wallet and ended lifecycle even when original bytes return`() = runTest {
        listOf("wallet", "lifecycle", "store").forEach { changed ->
            walletIndex = 0
            admitted = true
            val store = InMemoryDataStore<Preferences>(initial())
            val credentials = credentials(store)
            val target = credentials.capture(::requireCurrent)
            store.edit { it[sharedSource] = "unreadable" }
            assertFailsWith<IOException> { credentials.validate(target, ::requireCurrent) }
            store.updateData { initial() }
            when (changed) {
                "wallet" -> {
                    walletIndex = 1
                    assertFailsWith<ProfileCredentialsChanged> {
                        credentials.eraseAfterLocalConfirmation(target, ::requireCurrent)
                    }
                    walletIndex = 0
                }
                "lifecycle" -> {
                    admitted = false
                    assertFailsWith<IllegalStateException> {
                        credentials.eraseAfterLocalConfirmation(target, ::requireCurrent)
                    }
                    admitted = true
                }
                "store" -> assertFailsWith<ProfileCredentialsChanged> {
                    credentials(store).eraseAfterLocalConfirmation(target, ::requireCurrent)
                }
            }
            if (changed != "store") {
                assertFailsWith<ProfileCredentialsChanged> {
                    credentials.eraseAfterLocalConfirmation(target, ::requireCurrent)
                }
            }
            assertEquals(initial(), store.data.first())
        }
    }

    @Test
    fun `validation rejects changed absent or unreadable captured entries without writing`() = runTest {
        listOf(session, secret, sharedSource).forEach { changed ->
            listOf("replacement", null, "unreadable").forEach { replacement ->
                val store = InMemoryDataStore<Preferences>(initial())
                val credentials = credentials(store)
                val target = credentials.capture(::requireCurrent)
                store.edit {
                    if (replacement == null) it.remove(changed) else it[changed] = replacement
                }
                val before = store.data.first()
                if (replacement == "unreadable") {
                    assertFailsWith<IOException> { credentials.validate(target, ::requireCurrent) }
                } else {
                    assertFailsWith<ProfileCredentialsChanged> { credentials.validate(target, ::requireCurrent) }
                }
                assertEquals(before, store.data.first())
            }
        }
    }

    @Test
    fun `validation preserves equal reencrypted credentials but rejects store wallet or admission changes`() = runTest {
        val store = InMemoryDataStore<Preferences>(initial())
        val credentials = credentials(store)
        val target = credentials.capture(::requireCurrent)
        store.edit { it[session] = "different-iv:session" }
        val before = store.data.first()
        credentials.validate(target, ::requireCurrent)
        assertFailsWith<ProfileCredentialsChanged> { credentials(store).validate(target, ::requireCurrent) }
        walletIndex = 1
        assertFailsWith<ProfileCredentialsChanged> { credentials.validate(target, ::requireCurrent) }
        walletIndex = 0
        admitted = false
        assertFailsWith<IllegalStateException> { credentials.validate(target, ::requireCurrent) }
        assertEquals(before, store.data.first())
    }

    @Test
    fun `observed invalid owned credentials cannot regain erase permission when restored`() = runTest {
        listOf("replacement", "unreadable").forEach { replacement ->
            val store = InMemoryDataStore<Preferences>(initial())
            val credentials = credentials(store)
            val target = credentials.capture(::requireCurrent)
            store.edit { it[session] = replacement }
            if (replacement == "unreadable") {
                assertFailsWith<IOException> { credentials.validate(target, ::requireCurrent) }
            } else {
                assertFailsWith<ProfileCredentialsChanged> { credentials.validate(target, ::requireCurrent) }
            }
            store.updateData { initial() }
            assertFailsWith<ProfileCredentialsChanged> { credentials.erase(target, ::requireCurrent) }
            assertEquals(initial(), store.data.first())
        }
    }

    @Test
    fun `observed erase mismatch or decryption failure cannot regain permission when restored`() = runTest {
        listOf("replacement", "unreadable").forEach { replacement ->
            val store = InMemoryDataStore<Preferences>(initial())
            val credentials = credentials(store)
            val target = credentials.capture(::requireCurrent)
            store.edit { it[sharedSource] = replacement }
            if (replacement == "unreadable") {
                assertFailsWith<IOException> { credentials.erase(target, ::requireCurrent) }
            } else {
                assertFailsWith<ProfileCredentialsChanged> { credentials.erase(target, ::requireCurrent) }
            }
            store.updateData { initial() }
            assertFailsWith<ProfileCredentialsChanged> { credentials.erase(target, ::requireCurrent) }
            assertEquals(initial(), store.data.first())
        }
    }

    @Test
    fun `cancelled owned credential read preserves permission for matching local retry`() = runTest {
        val backing = InMemoryDataStore<Preferences>(initial())
        var cancelRead = false
        val store = object : DataStore<Preferences> {
            override val data = backing.data.map {
                if (cancelRead) throw CancellationException("Caller cancelled")
                it
            }

            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                backing.updateData(transform)
        }
        val credentials = credentials(store)
        val target = credentials.capture(::requireCurrent)
        cancelRead = true
        assertFailsWith<CancellationException> { credentials.validate(target, ::requireCurrent) }
        assertEquals(initial(), backing.data.first())
        cancelRead = false
        credentials.erase(target, ::requireCurrent)
        assertFalse(backing.data.first().contains(session))
    }

    @Test
    fun `failed read before erase transform permanently retires original target`() = runTest {
        val backing = InMemoryDataStore<Preferences>(initial())
        var refuseRead = true
        val store = object : DataStore<Preferences> {
            override val data = backing.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (refuseRead) throw IOException("Preferences cannot be read")
                return backing.updateData(transform)
            }
        }
        val credentials = credentials(store)
        val target = credentials.capture(::requireCurrent)
        assertFailsWith<IOException> { credentials.erase(target, ::requireCurrent) }
        assertEquals(initial(), backing.data.first())
        refuseRead = false
        assertFailsWith<ProfileCredentialsChanged> { credentials.erase(target, ::requireCurrent) }
        assertEquals(initial(), backing.data.first())
    }

    @Test
    fun `cancelled erase before transform preserves matching target for retry`() = runTest {
        val backing = InMemoryDataStore<Preferences>(initial())
        var cancelRead = true
        val store = object : DataStore<Preferences> {
            override val data = backing.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (cancelRead) throw CancellationException("Caller cancelled before read")
                return backing.updateData(transform)
            }
        }
        val credentials = credentials(store)
        val target = credentials.capture(::requireCurrent)
        assertFailsWith<CancellationException> { credentials.erase(target, ::requireCurrent) }
        assertEquals(initial(), backing.data.first())
        cancelRead = false
        credentials.erase(target, ::requireCurrent)
        assertFalse(backing.data.first().contains(session))
    }

    @Test
    fun `erase commits all original profile entries together and survives restart`() = runTest {
        val file = folder.root.resolve("keychain.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val store = PreferenceDataStoreFactory.create(scope = scope) { file }
        try {
            store.updateData { initial() }
            val credentials = credentials(store)
            val target = credentials.capture(::requireCurrent)
            credentials.erase(target, ::requireCurrent)
        } finally {
            requireNotNull(scope.coroutineContext[Job]).cancelAndJoin()
        }
        val reopened = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        assertEquals(
            mutablePreferencesOf(mnemonic to "unchanged-wallet", otherWallet to "other-wallet-session").asMap(),
            reopened.data.first().asMap(),
        )
    }

    @Test
    fun `changed or newly absent credentials refuse the entire erase`() = runTest {
        listOf(session, secret, sharedSource).forEach { changed ->
            listOf("replacement", null).forEach { replacement ->
                val store = InMemoryDataStore<Preferences>(initial())
                val credentials = credentials(store)
                val target = credentials.capture(::requireCurrent)
                store.edit {
                    if (replacement == null) it.remove(changed) else it[changed] = replacement
                }
                val before = store.data.first()
                assertFailsWith<ProfileCredentialsChanged> { credentials.erase(target, ::requireCurrent) }
                assertEquals(before, store.data.first())
            }
        }
    }

    @Test
    fun `adding an originally absent managed key refuses erase`() = runTest {
        val store = InMemoryDataStore<Preferences>(initial().also { it.remove(secret) })
        val credentials = credentials(store)
        val target = credentials.capture(::requireCurrent)
        store.edit { it[secret] = "replacement" }
        val before = store.data.first()
        assertFailsWith<ProfileCredentialsChanged> { credentials.erase(target, ::requireCurrent) }
        assertEquals(before, store.data.first())
    }

    @Test
    fun `wallet replacement or ended lifecycle refuses erase even with equal values`() = runTest {
        val store = InMemoryDataStore<Preferences>(initial())
        val credentials = credentials(store)
        val target = credentials.capture(::requireCurrent)
        walletIndex = 1
        assertFailsWith<ProfileCredentialsChanged> { credentials.erase(target, ::requireCurrent) }
        walletIndex = 0
        admitted = false
        assertFailsWith<IllegalStateException> { credentials.erase(target, ::requireCurrent) }
        assertEquals(initial(), store.data.first())
    }

    @Test
    fun `another credential store cannot use the target`() = runTest {
        val store = InMemoryDataStore<Preferences>(initial())
        val target = credentials(store).capture(::requireCurrent)
        assertFailsWith<ProfileCredentialsChanged> { credentials(store).erase(target, ::requireCurrent) }
        assertEquals(initial(), store.data.first())
    }

    @Test
    fun `re-encryption of the same bundle does not invalidate original credentials`() = runTest {
        val store = InMemoryDataStore<Preferences>(initial())
        val credentials = credentials(store)
        val target = credentials.capture(::requireCurrent)
        store.edit {
            it[session] = "new-iv:session"
            it[secret] = "new-iv:managed-key"
        }
        credentials.erase(target, ::requireCurrent)
        assertFalse(store.data.first().contains(session))
        assertEquals("unchanged-wallet", store.data.first()[mnemonic])
    }

    @Test
    fun `decryption failure never clears any entry`() = runTest {
        val store = InMemoryDataStore<Preferences>(initial())
        val credentials = credentials(store)
        val target = credentials.capture(::requireCurrent)
        store.edit { it[sharedSource] = "unreadable" }
        val before = store.data.first()
        assertFailsWith<IOException> { credentials.erase(target, ::requireCurrent) }
        assertFailsWith<IOException> { credentials.capture(::requireCurrent) }
        assertEquals(before, store.data.first())
    }

    @Test
    fun `failed durable update retains the original bundle and permits a later local retry`() = runTest {
        val backing = InMemoryDataStore<Preferences>(initial())
        var refuse = true
        val store = object : DataStore<Preferences> {
            override val data = backing.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                backing.updateData {
                    val next = transform(it)
                    if (refuse) throw IOException("Storage unavailable")
                    next
                }
        }
        val credentials = credentials(store)
        val target = credentials.capture(::requireCurrent)
        assertFailsWith<IOException> { credentials.erase(target, ::requireCurrent) }
        assertEquals(initial(), backing.data.first())
        refuse = false
        credentials.erase(target, ::requireCurrent)
        assertFalse(backing.data.first().contains(session))
    }
}
