package app.phonebridge

import java.security.MessageDigest

/** Pure (Android-free) logic, unit-tested in app/src/test. */
object AuditHash {
    const val GENESIS = "0000000000000000000000000000000000000000000000000000000000000000"

    /** shared/PROTOCOL.md: hex(sha256(UTF-8(prev + "\n" + join(U+001F, fields)))), null -> "". */
    fun of(prev: String, fields: List<Any?>): String {
        val s = prev + "\n" + fields.joinToString("\u001f") { it?.toString() ?: "" }
        return MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

object Paging {
    /**
     * Splits [items] into consecutive groups whose summed [size] stays <= [maxBytes] (and <= [maxItems] items).
     * An item bigger than [maxBytes] on its own still gets its own group, so nothing is ever dropped.
     */
    fun <T> chunkBySize(items: List<T>, maxBytes: Int, maxItems: Int = Int.MAX_VALUE, size: (T) -> Int): List<List<T>> {
        val out = mutableListOf<List<T>>()
        var cur = mutableListOf<T>()
        var bytes = 0
        for (it in items) {
            val n = size(it)
            if (cur.isNotEmpty() && (bytes + n > maxBytes || cur.size >= maxItems)) {
                out += cur; cur = mutableListOf(); bytes = 0
            }
            cur += it; bytes += n
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }
}

object StatusGate {
    const val HEARTBEAT_MS = 5 * 60_000L

    /** Send a status when its content changed, or as a heartbeat; never every retry loop while nothing moves. */
    fun shouldSend(prevSig: String?, prevAt: Long, sig: String, now: Long): Boolean =
        sig != prevSig || now - prevAt >= HEARTBEAT_MS
}

object Backoff {
    const val BASE_MS = 5_000L
    const val CAP_MS = 5 * 60_000L

    /** Delay before retry number [failures] (1-based): 5 s, 10 s, 20 s ... capped at 5 min. */
    fun delayMs(failures: Int): Long =
        if (failures <= 0) 0 else minOf(CAP_MS, BASE_MS shl minOf(failures - 1, 20))
}
