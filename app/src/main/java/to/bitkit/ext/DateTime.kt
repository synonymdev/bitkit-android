package to.bitkit.ext

import java.time.Instant
import java.time.temporal.ChronoUnit

fun nowTimestamp(): Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)
