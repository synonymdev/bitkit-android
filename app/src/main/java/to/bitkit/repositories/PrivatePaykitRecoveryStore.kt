package to.bitkit.repositories

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import to.bitkit.data.keychain.Keychain
import to.bitkit.di.json
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.services.PubkyService
import to.bitkit.utils.Logger
import java.security.MessageDigest

internal class PrivatePaykitRecoveryStore(
    private val pubkyService: PubkyService,
    private val keychain: Keychain,
    private val stateProvider: suspend () -> PrivatePaykitState,
) {
    companion object {
        private const val TAG = "PrivatePaykitRecoveryStore"
        private const val PRIVATE_STORAGE_ROOT_PATH = "/pub/paykit/v0/private/"
        private const val PRIVATE_STORAGE_PURGE_MAX_ENTRIES = 500
        private const val PRIVATE_STORAGE_PURGE_MAX_DEPTH = 3
    }

    @Suppress("ReturnCount")
    suspend fun freshRecoveryMarker(
        from: String,
        to: String,
        stages: Set<String>,
        attemptId: String? = null,
    ): RecoveryMarker? {
        val markerUri = recoveryMarkerUri(from, to) ?: return null
        val markerPath = recoveryMarkerPath(from, to) ?: return null
        val marker = runCatching {
            json.decodeFromString<RecoveryMarker>(pubkyService.fetchFileString(markerUri))
        }.getOrNull() ?: return null

        if (marker.version != 1) return null
        if (marker.path != markerPath) return null
        if (marker.stage !in stages) return null
        if (marker.attemptId.isBlank()) return null

        val state = stateProvider()
        val contactKey = listOf(from, to)
            .mapNotNull { normalizedPublicKey(it) }
            .firstOrNull { state.contacts[it] != null }
        val linkCompletedAt = contactKey?.let { state.contacts[it]?.linkCompletedAt } ?: 0L
        if (marker.createdAt <= linkCompletedAt) return null
        if (attemptId != null && marker.attemptId != attemptId) return null
        return marker
    }

    suspend fun publishRecoveryMarker(
        from: String,
        to: String,
        stage: String,
        attemptId: String,
        createdAt: Long,
    ) {
        val markerPath = recoveryMarkerPath(from, to) ?: return
        val sessionSecret = keychain.loadString(Keychain.Key.PAYKIT_SESSION.name) ?: return
        if (sessionSecret.isBlank() || attemptId.isBlank()) return

        val marker = RecoveryMarker(
            version = 1,
            path = markerPath,
            stage = stage,
            attemptId = attemptId,
            createdAt = createdAt,
        )
        runCatching {
            pubkyService.sessionPut(sessionSecret, markerPath, json.encodeToString(marker).encodeToByteArray())
        }.onFailure {
            Logger.warn(
                "Failed to publish private Paykit recovery marker for '${redacted(to)}'",
                it,
                context = TAG,
            )
        }
    }

    suspend fun clearRecoveryMarker(from: String, to: String) {
        val markerPath = recoveryMarkerPath(from, to) ?: return
        val sessionSecret = keychain.loadString(Keychain.Key.PAYKIT_SESSION.name) ?: return
        if (sessionSecret.isBlank()) return
        runCatching { pubkyService.sessionDelete(sessionSecret, markerPath) }
    }

    @Suppress("ReturnCount")
    suspend fun purgePrivatePaymentOutbox(publicKey: String, reason: String): Boolean {
        val otherContactCount = stateProvider().contacts.keys.count { it != publicKey }
        if (otherContactCount > 0) {
            Logger.warn(
                "Skipping broad private Paykit transport cleanup during '$reason' because " +
                    "'$otherContactCount' other private contact(s) have state",
                context = TAG,
            )
            return true
        }

        val sessionSecret = keychain.loadString(Keychain.Key.PAYKIT_SESSION.name) ?: return false
        if (sessionSecret.isBlank()) return false
        val rootPath = PRIVATE_STORAGE_ROOT_PATH.removeSuffix("/")
        val deletedRoot = runCatching {
            pubkyService.sessionDelete(sessionSecret, rootPath)
        }.onSuccess {
            Logger.info("Cleared stale private Paykit transport directory during '$reason'", context = TAG)
        }.onFailure {
            if (!isMissingPrivateStorageError(it)) {
                Logger.warn("Failed to clear private Paykit transport directory during '$reason'", it, context = TAG)
            }
        }.isSuccess
        if (deletedRoot) return true

        val purgeResult = runCatching {
            purgePrivatePaymentStorageTree(sessionSecret, PRIVATE_STORAGE_ROOT_PATH, depth = 0, deletedSoFar = 0)
        }.getOrElse {
            if (!isMissingPrivateStorageError(it)) {
                Logger.warn("Failed to purge private Paykit transport messages during '$reason'", it, context = TAG)
                return false
            }
            return true
        }
        if (purgeResult.deletedCount > 0) {
            Logger.info(
                "Cleared '${purgeResult.deletedCount}' stale private Paykit transport messages during '$reason'",
                context = TAG,
            )
        }
        if (purgeResult.didHitLimit) {
            Logger.warn("Stopped private Paykit transport cleanup after reaching the safety limit", context = TAG)
        }
        return !purgeResult.didHitLimit && !purgeResult.didFail
    }

    private suspend fun purgePrivatePaymentStorageTree(
        sessionSecret: String,
        dirPath: String,
        depth: Int,
        deletedSoFar: Int,
    ): PrivateStoragePurgeResult {
        if (deletedSoFar >= PRIVATE_STORAGE_PURGE_MAX_ENTRIES) {
            return PrivateStoragePurgeResult(deletedCount = 0, didHitLimit = true, didFail = false)
        }
        if (depth >= PRIVATE_STORAGE_PURGE_MAX_DEPTH) {
            return PrivateStoragePurgeResult(deletedCount = 0, didHitLimit = true, didFail = false)
        }

        val entries = pubkyService.sessionList(sessionSecret, dirPath.withTrailingSlash())
        var deletedCount = 0
        var didHitLimit = false
        var didFail = false

        entries.forEach {
            if (deletedSoFar + deletedCount >= PRIVATE_STORAGE_PURGE_MAX_ENTRIES) {
                didHitLimit = true
                return@forEach
            }
            val path = privateStoragePath(it) ?: return@forEach
            val deleted = runCatching {
                pubkyService.sessionDelete(sessionSecret, path.removeSuffix("/"))
            }.isSuccess
            if (deleted) {
                deletedCount += 1
                return@forEach
            }

            val childResult = runCatching {
                purgePrivatePaymentStorageTree(
                    sessionSecret = sessionSecret,
                    dirPath = path.withTrailingSlash(),
                    depth = depth + 1,
                    deletedSoFar = deletedSoFar + deletedCount,
                )
            }.getOrElse { error ->
                if (!isMissingPrivateStorageError(error)) didFail = true
                return@forEach
            }
            deletedCount += childResult.deletedCount
            didHitLimit = didHitLimit || childResult.didHitLimit
            didFail = didFail || childResult.didFail
        }

        return PrivateStoragePurgeResult(
            deletedCount = deletedCount,
            didHitLimit = didHitLimit,
            didFail = didFail,
        )
    }

    private fun privateStoragePath(entry: String): String? {
        val path = if (entry.startsWith("pubky://")) {
            "/${entry.substringAfter("://").substringAfter("/")}"
        } else {
            entry
        }
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        return normalizedPath.takeIf { it.startsWith(PRIVATE_STORAGE_ROOT_PATH) }
    }

    private fun recoveryMarkerPath(writerPublicKey: String, readerPublicKey: String): String? {
        val writer = normalizedPublicKey(writerPublicKey) ?: return null
        val reader = normalizedPublicKey(readerPublicKey) ?: return null
        val material = "bitkit-private-paykit-recovery-v1|$writer|$reader"
        val markerId = MessageDigest.getInstance("SHA-256")
            .digest(material.encodeToByteArray())
            .joinToString(separator = "") { "%02x".format(it) }
        return "/pub/paykit/v0/private-recovery/$markerId.json"
    }

    private fun recoveryMarkerUri(writerPublicKey: String, readerPublicKey: String): String? {
        val writer = normalizedPublicKey(writerPublicKey) ?: return null
        val path = recoveryMarkerPath(writer, readerPublicKey) ?: return null
        return "pubky://${writer.removePrefix("pubky")}$path"
    }

    private fun isMissingPrivateStorageError(error: Throwable): Boolean {
        val reason = error.message.orEmpty().lowercase()
        return "404" in reason && "not found" in reason
    }

    private fun String.withTrailingSlash(): String = if (endsWith("/")) this else "$this/"

    private fun normalizedPublicKey(publicKey: String): String? = PubkyPublicKeyFormat.normalized(publicKey)

    private fun redacted(publicKey: String): String = PubkyPublicKeyFormat.redacted(publicKey)
}
