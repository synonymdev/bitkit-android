package to.bitkit.repositories

import com.synonym.bitkitcore.decodeCompactSeedQr
import com.synonym.bitkitcore.decodeStandardSeedQr
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.QrCodePayload
import to.bitkit.utils.AppError
import javax.inject.Inject

class SeedQrRepo @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun decode(payload: QrCodePayload): Result<String> = withContext(ioDispatcher) {
        runSuspendCatching {
            when {
                payload.text?.matches(STANDARD_SEED_QR_PATTERN) == true -> {
                    decodeStandardSeedQr(payload.text)
                }

                payload.rawBytes?.size == COMPACT_SEED_QR_LENGTH -> {
                    decodeCompactSeedQr(payload.rawBytes)
                }

                else -> throw SeedQrImportError.InvalidPayload()
            }
        }
    }
}

sealed class SeedQrImportError : AppError() {
    class InvalidPayload : SeedQrImportError()
}

private const val COMPACT_SEED_QR_LENGTH = 16
private val STANDARD_SEED_QR_PATTERN = Regex("[0-9]{48}")
