package app.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    // Same entries as android/tools/audit_vector.py; the expected hashes are that script's output (Python hashlib).
    private val text = "سلام! کد شما ۱۲۳۴ است، ممنون‌ام"
    private val e1 = listOf<Any?>(1L, 1767225600000L, "c1a2b3", "hermes", "+989000000101", text, 2, "sent", null)
    private val e2 = listOf<Any?>(2L, 1767225660000L, "c1a2b3", "hermes", "+989000000101", text, 2, "delivered", null)
    private val pyH1 = "09ee7ee9d9844a71e11569d4c91d3a226b58368ad088dab7d06ef9ab68c274e4"
    private val pyH2 = "22d94969207a37f3b505892872461714159069137e62b7c1aa358166446ea07d"

    @Test fun auditHashMatchesPythonIncludingPersianText() {
        assertEquals(64, AuditHash.GENESIS.length)
        val h1 = AuditHash.of(AuditHash.GENESIS, e1)
        assertEquals(pyH1, h1)
        assertEquals(pyH2, AuditHash.of(h1, e2))
    }

    @Test fun auditHashChangesWhenAnyFieldOrPrevChanges() {
        val base = AuditHash.of(AuditHash.GENESIS, e1)
        for (i in e1.indices) {
            val tampered = e1.toMutableList().also { it[i] = "x" }
            assertNotEquals("field $i", base, AuditHash.of(AuditHash.GENESIS, tampered))
        }
        assertNotEquals(base, AuditHash.of(pyH2, e1))
    }

    @Test fun chunkBySizeKeepsOrderAndRespectsLimits() {
        val items = (1..50).map { "x".repeat(it * 10) } // 10..500 bytes, 12750 total
        val chunks = Paging.chunkBySize(items, maxBytes = 1000, maxItems = 7) { it.length }
        assertEquals(items, chunks.flatten()) // nothing dropped, order kept
        assertTrue(chunks.all { c -> c.sumOf { it.length } <= 1000 && c.size <= 7 })
        assertTrue(chunks.size >= 13) // 12750 bytes cannot fit in fewer 1000-byte chunks
        // each chunk is full: adding the next item would have broken a limit
        chunks.zipWithNext().forEach { (a, b) -> assertTrue(a.sumOf { it.length } + b.first().length > 1000 || a.size == 7) }
    }

    @Test fun oversizedItemGetsItsOwnChunk() {
        val chunks = Paging.chunkBySize(listOf(10, 5000, 10, 10), maxBytes = 100) { it }
        assertEquals(listOf(listOf(10), listOf(5000), listOf(10, 10)), chunks)
        assertEquals(emptyList<List<Int>>(), Paging.chunkBySize(emptyList<Int>(), 100) { it })
    }

    @Test fun statusIsSentOnChangeOrHeartbeatOnly() {
        val t0 = 1_000_000L
        assertTrue(StatusGate.shouldSend(null, 0, "a", t0)) // first ever
        assertTrue(!StatusGate.shouldSend("a", t0, "a", t0 + 15_000)) // retry loop, nothing changed: suppressed
        assertTrue(!StatusGate.shouldSend("a", t0, "a", t0 + StatusGate.HEARTBEAT_MS - 1))
        assertTrue(StatusGate.shouldSend("a", t0, "a", t0 + StatusGate.HEARTBEAT_MS)) // heartbeat
        assertTrue(StatusGate.shouldSend("a", t0, "b", t0 + 1)) // progress changed
    }

    @Test fun backoffDoublesAndCapsAtFiveMinutes() {
        assertEquals(listOf(0L, 5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 160_000L, 300_000L, 300_000L),
            (0..8).map { Backoff.delayMs(it) })
        assertEquals(300_000L, Backoff.delayMs(1000)) // no overflow for long outages
    }
}
