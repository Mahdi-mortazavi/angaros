package app.phonebridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ModelTest {
    private fun row(seq: Long, id: String, result: String, error: String? = null) =
        AuditRow(seq, 1_000L * seq, id, "hermes", "0912", "salam", 1, result, error, "h$seq")

    @Test fun connStatePrecedence() {
        assertEquals(Conn.PAUSED, connState(paused = true, relayMs = -1, backfillComplete = false))
        assertEquals(Conn.CHECKING, connState(false, null, true))
        assertEquals(Conn.OFFLINE, connState(false, -1, false))
        assertEquals(Conn.SYNCING, connState(false, 120, false))
        assertEquals(Conn.CONNECTED, connState(false, 120, true))
    }

    /** Seen on the A30s: one dashboard ping timed out over the VPN while the long-poll was 7 s old -> "Offline". */
    @Test fun failedPingWithFreshPollIsNotOffline() {
        assertEquals(Conn.CONNECTED, connState(false, -1, true, pollFresh = true))
        assertEquals(Conn.SYNCING, connState(false, -1, false, pollFresh = true))
        assertEquals(Conn.CONNECTED, connState(false, null, true, pollFresh = true))
        assertEquals(Conn.OFFLINE, connState(false, -1, true, pollFresh = false))
        assertEquals(Conn.PAUSED, connState(true, -1, true, pollFresh = true))
    }

    @Test fun backfillFractionIgnoresDisabledKinds() {
        val p = mapOf("sms" to (50L to 100L), "calls" to (0L to 900L), "contacts" to (10L to 10L))
        assertEquals(60f / 110f, backfillFraction(p, setOf("sms", "contacts")), 1e-6f)
        assertEquals(1f, backfillFraction(p, emptySet()), 0f)
        assertEquals(1f, backfillFraction(mapOf("sms" to (120L to 100L)), setOf("sms")), 0f) // done>total is clamped
    }

    @Test fun groupSendsFoldsTheChainPerCommand() {
        val rows = listOf(
            row(1, "a", "sent"), row(2, "b", "failed", "radio error 1"), row(3, "a", "delivered"),
            row(4, "c", "expired", "expired"), row(5, "d", "sent"), row(6, "d", "delivery_failed", "1 of 1 parts not delivered"),
        ).reversed() // the DB returns newest first
        val g = groupSends(rows).associateBy { it.id }
        assertEquals(listOf("d", "c", "a", "b"), groupSends(rows).map { it.id }) // newest activity first
        assertEquals(SendStatus.DELIVERED, g.getValue("a").status)
        assertNull(g.getValue("a").error)
        assertEquals(2, g.getValue("a").entries.size)
        assertEquals(SendStatus.FAILED, g.getValue("b").status)
        assertEquals("radio error 1", g.getValue("b").error)
        assertEquals(SendStatus.EXPIRED, g.getValue("c").status)
        assertEquals(SendStatus.NOT_DELIVERED, g.getValue("d").status)
        assertEquals("1 of 1 parts not delivered", g.getValue("d").error)
    }

    @Test fun countTodayClassifiesInboxOnly() {
        val c = countToday(sequenceOf(
            Triple(1, "کد تایید شما ۴۵۶۷۸۹ است", "10004321"),
            Triple(1, "بانک ملی ایران\nبرداشت:1,500,000\nمانده:25,300,000", "+98700"),
            Triple(1, "سلام داداش", "09000000101"),
            Triple(2, "کد تایید شما ۴۵۶۷۸۹ است", "0912"), // sent: counted as sent, never as an OTP
        ))
        assertEquals(Counters(received = 3, sent = 1, otp = 1, bank = 1), c)
    }

    @Test fun parseRulesIsTolerant() {
        assertEquals(null to emptyList<Rule>(), parseRules(null))
        val (at, rules) = parseRules(JSONObject("""{"received":5,"rules":[{"name":"deposit","trigger":"bank deposit > 1000000",
            "action":"webhook","enabled":true,"last_run":1700000000000,"last_ok":false,"runs":3},{"trigger":"cron 0 22 * * *"}]}"""))
        assertEquals(5L, at)
        assertEquals(2, rules.size)
        assertEquals(Rule("deposit", "bank deposit > 1000000", "webhook", true, 1700000000000, false, 3), rules[0])
        assertEquals("rule 2", rules[1].name)
        assertNull(rules[1].lastOk)
        assertNull(rules[1].lastRun)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FeedViewModelTest {
    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun reset() = Dispatchers.resetMain()

    /** 1000 messages, newest first; ids 1000..1 but only 100 distinct dates (10 per timestamp); every 10th id is an OTP. */
    private val all = (1000 downTo 1).map { d ->
        val otp = d % 10 == 0
        Msg(d.toLong(), false, "0912", null, "x", (d + 9L) / 10, true,
            Parse.Result(if (otp) Parse.Cls.OTP else Parse.Cls.PERSONAL, otp = if (otp) "1234" else null))
    }
    private var calls = 0
    private val load: suspend (FeedCursor?, Int) -> List<Msg> = { after, limit ->
        calls++
        all.filter { after == null || it.date < after.date || (it.date == after.date && it.id < after.id) }.take(limit)
    }

    @Test fun pagesWithoutFilterAndStopsAtTheEnd() {
        val vm = FeedViewModel(load, { true }, pageSize = 395) // 395 splits a 10-message timestamp group
        assertEquals(395, vm.ui.value.items.size)
        assertEquals(1000L, vm.ui.value.items.first().id)
        vm.loadMore(); vm.loadMore()
        assertEquals(1000, vm.ui.value.items.size)
        vm.loadMore()
        assertTrue(vm.ui.value.end)
        assertEquals(all.map { it.id }, vm.ui.value.items.map { it.id }) // no gaps, no duplicates across pages
    }

    @Test fun filterScansAcrossPagesButRespectsTheCap() {
        val vm = FeedViewModel(load, { true }, pageSize = 40, scanCap = 150)
        vm.setFilter(Parse.Cls.OTP)
        val s = vm.ui.value
        assertTrue(s.items.all { it.parsed.cls == Parse.Cls.OTP })
        // One 200-row page (>= cap 150) holds 20 OTPs (< pageSize 40): the cap stopped the scan before the end.
        assertEquals(20, s.items.size)
        assertFalse(s.end)
        assertFalse(s.loading)
    }

    @Test fun feedDaysGroupsByLocalDayAndPreformatsEveryString() {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val day = 86_400_000L
        val msgs = listOf(
            Msg(3, true, "0912", "Ali", "a\nb", 2 * day + 3_600_000, true, Parse.Result(Parse.Cls.PERSONAL)),
            Msg(2, false, "0935", null, "code", 2 * day + 60_000, true, Parse.Result(Parse.Cls.OTP, otp = "1234")),
            Msg(1, false, "0936", null, "old", day + 5, true, Parse.Result(Parse.Cls.AD)),
        )
        val days = feedDays(msgs, Fmt(persianDigits = true, jalali = false, fa = false, tz = utc), "You")
        assertEquals(listOf(2 * day, day), days.map { it.start })
        assertEquals(listOf(3L, 2L), days[0].rows.map { it.msg.id })
        val (out, otp) = days[0].rows
        assertEquals("You: ${isolate("a b")}", out.preview)
        assertEquals(isolate("Ali"), out.title)
        assertEquals("A", out.letter)
        assertEquals("۰۱:۰۰", out.time)
        assertEquals(ltr("۰۹۳۵"), otp.title)
        assertEquals("۱۲۳۴", otp.code)
        assertNull(otp.letter)
        assertEquals("۳ Jan ۱۹۷۰", days[0].label)
        // Only what two lines can show is shaped; a cut never leaves half a surrogate pair (emoji).
        val long = Msg(9, false, "1", null, "x".repeat(PREVIEW_CHARS - 1) + "😀" + "y".repeat(500), 0, true, Parse.Result(Parse.Cls.AD))
        assertEquals("x".repeat(PREVIEW_CHARS - 1), feedDays(listOf(long), Fmt(false, false, false, utc), "You")[0].rows[0].preview)
    }

    @Test fun noPermissionShowsThePermissionState() {
        val vm = FeedViewModel(load, { false })
        assertFalse(vm.ui.value.permission)
    }
}
