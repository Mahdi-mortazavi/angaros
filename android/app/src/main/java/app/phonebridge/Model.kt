package app.phonebridge

/** Pure UI models and the state derivations the ViewModels use. No Android imports: unit-tested on the JVM. */

data class AuditRow(val seq: Long, val ts: Long, val id: String, val agent: String, val number: String, val text: String,
                    val sim: Int?, val result: String, val error: String?, val hash: String)

enum class Conn { PAUSED, CHECKING, OFFLINE, SYNCING, CONNECTED }

/**
 * [relayMs]: null = not checked yet, < 0 = unreachable, else round-trip ms. [pollFresh]: the service's long-poll
 * reached the relay in the last ~2 minutes; one failed dashboard ping (a VPN hiccup) then does not mean offline.
 */
fun connState(paused: Boolean, relayMs: Long?, backfillComplete: Boolean, pollFresh: Boolean = false): Conn = when {
    paused -> Conn.PAUSED
    relayMs == null -> if (pollFresh) (if (backfillComplete) Conn.CONNECTED else Conn.SYNCING) else Conn.CHECKING
    relayMs < 0 && !pollFresh -> Conn.OFFLINE
    !backfillComplete -> Conn.SYNCING
    else -> Conn.CONNECTED
}

/** Overall history progress 0..1 over the kinds that are synced; 1 when there is nothing to do. */
fun backfillFraction(progress: Map<String, Pair<Long, Long>>, enabled: Set<String>): Float {
    val p = progress.filterKeys { it in enabled }.values
    val total = p.sumOf { it.second }
    if (total <= 0) return 1f
    return (p.sumOf { minOf(it.first, it.second) }.toFloat() / total).coerceIn(0f, 1f)
}

enum class SendStatus { PENDING, SENT, DELIVERED, NOT_DELIVERED, FAILED, EXPIRED }

data class SendItem(val id: String, val ts: Long, val agent: String, val number: String, val text: String, val sim: Int?,
                    val status: SendStatus, val error: String?, val entries: List<AuditRow>)

/**
 * Folds the audit log (newest first) into one item per command id, newest first. The delivery report, when
 * present, decides the status; otherwise the send result does.
 */
fun groupSends(rows: List<AuditRow>): List<SendItem> = rows.groupBy { it.id }.map { (id, es) ->
    val ordered = es.sortedBy { it.seq }
    val first = ordered.first()
    val dlv = ordered.lastOrNull { it.result == "delivered" || it.result == "delivery_failed" }
    val res = ordered.lastOrNull { it.result in setOf("sent", "failed", "expired") }
    val status = when {
        dlv?.result == "delivered" -> SendStatus.DELIVERED
        dlv != null -> SendStatus.NOT_DELIVERED
        res?.result == "sent" -> SendStatus.SENT
        res?.result == "expired" -> SendStatus.EXPIRED
        res != null -> SendStatus.FAILED
        else -> SendStatus.PENDING
    }
    SendItem(id, first.ts, first.agent, first.number, first.text, first.sim, status,
        (dlv?.error ?: res?.error)?.takeIf { status != SendStatus.SENT && status != SendStatus.DELIVERED }, ordered)
}.sortedByDescending { it.entries.last().seq }

sealed interface ChainState {
    data object Checking : ChainState
    data object Empty : ChainState
    data class Ok(val entries: Long) : ChainState
    data class Broken(val atSeq: Long) : ChainState
}

data class Counters(val received: Int = 0, val sent: Int = 0, val otp: Int = 0, val bank: Int = 0)

/** Today's counters from (box, body, sender) rows; box 1 = inbox, 2 = sent. */
fun countToday(rows: Sequence<Triple<Int, String, String>>): Counters {
    var c = Counters()
    for ((box, body, sender) in rows) {
        c = if (box == 2) c.copy(sent = c.sent + 1) else {
            val r = Parse.classify(body, sender)
            c.copy(received = c.received + 1, otp = c.otp + (if (r.cls == Parse.Cls.OTP) 1 else 0),
                bank = c.bank + (if (r.cls == Parse.Cls.BANK) 1 else 0))
        }
    }
    return c
}

/** Position in the feed: (date, _id) so messages sharing a timestamp are neither skipped nor repeated across pages. */
data class FeedCursor(val date: Long, val id: Long)

data class Msg(val id: Long, val outgoing: Boolean, val number: String, val name: String?, val body: String, val date: Long,
               val read: Boolean, val parsed: Parse.Result)

/** A feed row's display strings, formatted once off the main thread instead of on every scroll into view. */
data class FeedRow(val msg: Msg, val title: String, val preview: String, val time: String, val code: String?, val letter: String?)

data class FeedDay(val start: Long, val label: String, val rows: List<FeedRow>)

/** More than two lines hold even in landscape at font scale 1.0 (~100 chars a line on the A30s). */
const val PREVIEW_CHARS = 240

/** [msgs] (newest first) grouped by local day, with every string the row draws; [you] prefixes outgoing messages. */
fun feedDays(msgs: List<Msg>, fmt: Fmt, you: String): List<FeedDay> {
    val days = LinkedHashMap<Long, MutableList<FeedRow>>()
    for (m in msgs) {
        // The row shows two lines, but a Text shapes its whole string before ellipsizing: long bank/ad SMS cost frames.
        val cut = m.body.take(PREVIEW_CHARS).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
        val oneLine = cut.replace('\n', ' ')
        // The "You:" prefix is in the UI language; the isolate keeps a Persian body RTL behind an English prefix.
        val preview = if (m.outgoing) "$you: ${isolate(oneLine)}" else oneLine
        val title = if (m.name != null) isolate(m.name) else ltr(fmt.digits(m.number))
        val letter = if (m.parsed.cls == Parse.Cls.PERSONAL && m.name != null) m.name.trim().take(1) else null
        days.getOrPut(fmt.startOfDay(m.date)) { mutableListOf() } +=
            FeedRow(m, title, preview, fmt.time(m.date), m.parsed.otp?.let(fmt::digits), letter)
    }
    return days.map { (start, rows) -> FeedDay(start, fmt.date(start), rows) }
}

/** Left-to-right isolate (U+2066..U+2069) for numbers and URLs inside Persian text. */
fun ltr(s: String) = "${Char(0x2066)}$s${Char(0x2069)}"

/** First-strong isolate (U+2068..U+2069): the run keeps its own direction inside the surrounding text. */
fun isolate(s: String) = "${Char(0x2068)}$s${Char(0x2069)}"

data class Rule(val name: String, val trigger: String, val action: String, val enabled: Boolean, val lastRun: Long?,
                val lastOk: Boolean?, val runs: Int)

/** Parses the stored PROTOCOL.md v3 `rules_mirror` payload; tolerant of missing fields. */
fun parseRules(json: org.json.JSONObject?): Pair<Long?, List<Rule>> {
    if (json == null) return null to emptyList()
    val arr = json.optJSONArray("rules") ?: return null to emptyList()
    val rules = (0 until arr.length()).mapNotNull { i ->
        val r = arr.optJSONObject(i) ?: return@mapNotNull null
        Rule(r.optString("name", "rule ${i + 1}"), r.optString("trigger"), r.optString("action"), r.optBoolean("enabled", true),
            r.optLong("last_run", 0).takeIf { it > 0 }, if (r.isNull("last_ok") || !r.has("last_ok")) null else r.optBoolean("last_ok"),
            r.optInt("runs", 0))
    }
    return json.optLong("received", 0).takeIf { it > 0 } to rules
}
