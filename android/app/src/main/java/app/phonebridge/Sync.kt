package app.phonebridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.PowerManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.SubscriptionManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads SMS / call log / contacts into the outbox.
 * Backfill is newest-first and resumable: at start `top` = newest provider id and `bottom` = top+1; each page uploads
 * the next rows below `bottom` (descending) and lowers `bottom`. Rows above `top` are deltas, uploaded ascending.
 * Cursors only move after the rows are in the persistent outbox, so a kill mid-way re-reads at most one page
 * (the PC dedupes).
 */
class Sync(private val ctx: Context) {
    private val st = Store(ctx)
    private val db = Db.get(ctx)
    private val cr = ctx.contentResolver

    private fun has(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    /** Permission granted AND allowed by the owner's privacy toggle. */
    private fun may(kind: String, p: String) = has(p) && st.syncAllowed(kind)

    private class Src(val kind: String, val uri: Uri, val base: String?, val idCol: String, val perm: String,
                      val projection: Array<String>, val row: (Cursor) -> JSONObject)

    private fun Cursor.s(c: String) = getColumnIndex(c).let { if (it < 0 || isNull(it)) null else getString(it) }
    private fun Cursor.l(c: String) = getColumnIndex(c).let { if (it < 0 || isNull(it)) 0L else getLong(it) }

    private val sms = Src("sms", Uri.parse("content://sms"), "type IN (1,2)", "_id", Manifest.permission.READ_SMS,
        arrayOf("_id", "type", "address", "body", "date", "date_sent", "read", "thread_id")) { c ->
        JSONObject().put("pid", c.l("_id")).put("box", if (c.l("type") == 2L) "sent" else "inbox")
            .put("number", c.s("address") ?: "").put("body", c.s("body") ?: "")
            .put("date", c.l("date")).put("date_sent", c.l("date_sent")).put("read", c.l("read")).put("thread", c.l("thread_id"))
    }

    private val callTypes = mapOf(1 to "INCOMING", 2 to "OUTGOING", 3 to "MISSED", 4 to "VOICEMAIL", 5 to "REJECTED", 6 to "BLOCKED")
    private val calls = Src("calls", CallLog.Calls.CONTENT_URI, null, CallLog.Calls._ID, Manifest.permission.READ_CALL_LOG,
        arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION)) { c ->
        val type = c.l(CallLog.Calls.TYPE).toInt()
        JSONObject().put("pid", c.l(CallLog.Calls._ID)).put("number", c.s(CallLog.Calls.NUMBER) ?: "")
            .put("name", c.s(CallLog.Calls.CACHED_NAME) ?: JSONObject.NULL)
            .put("type", callTypes[type] ?: type.toString()).put("date", c.l(CallLog.Calls.DATE)).put("duration", c.l(CallLog.Calls.DURATION))
    }

    /** Delta + as much backfill as the network takes. Returns true when the backfill is complete. */
    @android.annotation.SuppressLint("ApplySharedPref") // the reset flag must survive a kill right after the request
    fun run(full: Boolean = false): Boolean {
        // A full resync is only requested here; the cursors are reset under LOCK by whichever run holds it
        // (this one, or a busy one that checks the flag between pages), so no run can overwrite a fresh reset.
        if (full && st.pairing != null) st.p.edit().putBoolean(RESET_FLAG, true).commit()
        if (!LOCK.tryLock()) return backfillComplete(ctx)
        try { work() } finally { LOCK.unlock() }
        return backfillComplete(ctx)
    }

    /** Caller holds LOCK. */
    @android.annotation.SuppressLint("ApplySharedPref") // cleared only after the cursors are reset, durably
    private fun applyReset() {
        if (!st.p.getBoolean(RESET_FLAG, false)) return
        st.resetSync()
        st.p.edit().remove(RESET_FLAG).commit()
        if (may("contacts", Manifest.permission.READ_CONTACTS)) contacts()
    }

    private fun work() {
        st.pairing ?: return
        if (st.paused) return
        try {
            applyReset()
            if (may("sms", sms.perm)) { delta(sms); outgoingPromotions(); unreadChanges() }
            if (may("calls", calls.perm)) delta(calls)
            if (may("contacts", Manifest.permission.READ_CONTACTS)) contacts()
            while (true) {
                applyReset()
                val moved = (may("sms", sms.perm) && backfillPage(sms)) || (may("calls", calls.perm) && backfillPage(calls))
                maybeStatus(ctx)
                if (!moved) break
                if (Outbox.flush(ctx) != 0L) break // relay unreachable: resume from the cursors next run
            }
            st.setLong("lastSync", System.currentTimeMillis())
        } catch (e: Exception) {
            st.error(e)
        }
    }

    /**
     * Outgoing rows still in outbox/failed/queued (type 4/5/6) are not uploaded; once one turns into `sent` its id may
     * already be below `top`, so delta() would never see it. Remember their ids and upload them when they change.
     */
    private fun outgoingPromotions() {
        val saved = st.str(PENDING_OUT)?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty()
        saved.chunked(500).forEach { ids ->
            val rows = cr.query(sms.uri, sms.projection, "type IN (1,2) AND _id IN (${ids.joinToString(",")})", null, null)?.take(500, sms.row).orEmpty()
            if (rows.isNotEmpty()) enqueueChunks("sms", rows)
        }
        val now = cr.query(sms.uri, arrayOf("_id"), "type IN (4,5,6)", null, null)?.take(5000) { it.getLong(0) } ?: return
        st.setStr(PENDING_OUT, now.joinToString(","))
    }

    /** Providers reject LIMIT in sortOrder on newer Android, so read [max] rows and close instead. */
    private fun <T> Cursor.take(max: Int, row: (Cursor) -> T): List<T> = use { c ->
        generateSequence { if (c.moveToNext()) row(c) else null }.take(max).toList()
    }

    private fun where(src: Src, cond: String) = if (src.base == null) cond else "${src.base} AND $cond"

    private fun enqueueChunks(t: String, rows: List<JSONObject>, extra: (Int, JSONObject) -> Unit = { _, _ -> }, after: (Int) -> Unit = {}) {
        Paging.chunkBySize(rows, MAX_CHUNK_BYTES, MAX_CHUNK_ITEMS) { it.toString().toByteArray().size + 1 }.forEachIndexed { i, ch ->
            Outbox.add(ctx, JSONObject().put("t", t).put("items", JSONArray(ch)).also { extra(i, it) })
            after(ch.size)
        }
    }

    private fun init(src: Src) {
        if (st.p.contains("${src.kind}.top")) return
        var top = 0L
        var total = 0L
        cr.query(src.uri, arrayOf(src.idCol), src.base, null, "${src.idCol} DESC")?.use { c ->
            total = c.count.toLong()
            if (c.moveToFirst()) top = c.getLong(0)
        } ?: return
        st.p.edit().putLong("${src.kind}.top", top).putLong("${src.kind}.bottom", top + 1)
            .putLong("${src.kind}.total", total).putLong("${src.kind}.done", 0).putBoolean("${src.kind}.bfDone", false).apply()
    }

    /** One page below `bottom`, newest first. Returns false when nothing was left. */
    private fun backfillPage(src: Src): Boolean {
        init(src)
        val k = src.kind
        if (st.p.getBoolean("$k.bfDone", false)) return false
        val bottom = st.long("$k.bottom")
        val rows = cr.query(src.uri, src.projection, where(src, "${src.idCol} < $bottom"), null, "${src.idCol} DESC")?.take(PAGE, src.row) ?: return false
        if (rows.isEmpty()) {
            st.p.edit().putBoolean("$k.bfDone", true).putLong("$k.done", st.long("$k.total")).apply()
            return false
        }
        enqueueChunks(k, rows)
        val done = minOf(st.long("$k.total"), st.long("$k.done") + rows.size)
        st.p.edit().putLong("$k.bottom", rows.minOf { it.getLong("pid") }).putLong("$k.done", done).apply()
        return true
    }

    /** Everything newer than `top`, ascending. */
    private fun delta(src: Src) {
        init(src)
        val k = src.kind
        while (true) {
            val top = st.long("$k.top")
            val rows = cr.query(src.uri, src.projection, where(src, "${src.idCol} > $top"), null, "${src.idCol} ASC")?.take(PAGE, src.row) ?: return
            if (rows.isEmpty()) return
            enqueueChunks(k, rows)
            st.setLong("$k.top", rows.maxOf { it.getLong("pid") })
            if (rows.size < PAGE) return
        }
    }

    /** Messages whose read flag changed since the last run: re-send their rows so `sms --unread` stays true. */
    private fun unreadChanges() {
        val uri = sms.uri
        val now = cr.query(uri, arrayOf("_id"), "type=1 AND read=0", null, null)?.take(Int.MAX_VALUE) { it.getLong(0) }?.toSet() ?: return
        val before = st.str("unread")?.split(',')?.mapNotNull { it.toLongOrNull() }?.toSet()
        if (before != null) {
            val changed = (before - now) + (now - before) // read on the phone, or marked unread again
            changed.chunked(500).forEach { ids ->
                val rows = cr.query(uri, sms.projection, "_id IN (${ids.joinToString(",")})", null, null)?.take(500, sms.row) ?: emptyList()
                enqueueChunks("sms", rows)
            }
        }
        st.setStr("unread", now.joinToString(","))
    }

    private fun contacts() {
        val byId = linkedMapOf<Long, Pair<String, MutableList<String>>>()
        cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val e = byId.getOrPut(c.getLong(0)) { (c.getString(1) ?: "") to mutableListOf() }
                c.getString(2)?.let { if (it !in e.second) e.second += it }
            }
        } ?: return
        val hashes = byId.mapValues { (_, v) -> (v.first + "|" + v.second.sorted().joinToString(",")).hashCode().toString() }
        val stored = st.str("contacts.hashes")
        val full = stored == null
        val old = if (full) emptyMap() else JSONObject(stored).let { j -> j.keys().asSequence().associate { it.toLong() to j.getString(it) } }
        val changed = byId.filterKeys { full || old[it] != hashes[it] }
            .map { (id, v) -> JSONObject().put("cid", id).put("name", v.first).put("numbers", JSONArray(v.second)) }
        val deleted = old.keys - byId.keys
        if (full) st.p.edit().putLong("contacts.total", byId.size.toLong()).putLong("contacts.done", 0).apply()
        if (changed.isEmpty() && deleted.isEmpty() && !full) return
        if (changed.isEmpty()) {
            Outbox.add(ctx, JSONObject().put("t", "contacts").put("full", full).put("items", JSONArray()).put("deleted", JSONArray(deleted.toList())))
        } else enqueueChunks("contacts", changed, extra = { i, m ->
            // full=true only on the first chunk: it tells the PC to drop its copy before refilling.
            m.put("full", full && i == 0).put("deleted", JSONArray(if (i == 0) deleted.toList() else emptyList<Long>()))
        }, after = { n -> if (full) st.setLong("contacts.done", st.long("contacts.done") + n) })
        st.p.edit().putString("contacts.hashes", JSONObject(hashes.mapKeys { it.key.toString() }).toString())
            .putLong("contacts.total", byId.size.toLong()).putLong("contacts.done", byId.size.toLong()).apply()
    }

    companion object {
        private val LOCK = java.util.concurrent.locks.ReentrantLock()
        const val PAGE = 1000
        /** Plaintext bytes per message: base64 + GCM keeps the blob ~135 KB, well under the relay's 256 KB. */
        const val MAX_CHUNK_BYTES = 100_000
        const val MAX_CHUNK_ITEMS = 500

        fun progress(st: Store, k: String) = st.long("$k.done") to st.long("$k.total")

        private const val RESET_FLAG = "resetPending"
        private const val PENDING_OUT = "sms.pendingOut"

        private fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

        /** A kind whose permission is denied, or that the owner excluded, counts as done: nothing will be uploaded. */
        fun backfillComplete(ctx: Context): Boolean {
            val st = Store(ctx)
            fun off(kind: String, p: String) = !granted(ctx, p) || !st.syncAllowed(kind)
            return (st.p.getBoolean("sms.bfDone", false) || off("sms", Manifest.permission.READ_SMS)) &&
                (st.p.getBoolean("calls.bfDone", false) || off("calls", Manifest.permission.READ_CALL_LOG)) &&
                (st.str("contacts.hashes") != null || off("contacts", Manifest.permission.READ_CONTACTS))
        }

        /** Enqueues a status only when it changed (ignoring ts/queue) or as a heartbeat; see [StatusGate]. */
        fun maybeStatus(ctx: Context) {
            val st = Store(ctx)
            val s = status(ctx)
            val sig = JSONObject(s.toString()).apply { remove("ts"); remove("queue") }.toString()
            val now = System.currentTimeMillis()
            if (!StatusGate.shouldSend(st.str("statusSig"), st.long("statusAt"), sig, now)) return
            Outbox.add(ctx, s)
            st.p.edit().putString("statusSig", sig).putLong("statusAt", now).apply()
        }

        fun batteryUnrestricted(ctx: Context) = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

        /** Active SIMs as (slot 1-based, display name). Empty without READ_PHONE_STATE. */
        fun sims(ctx: Context): List<Pair<Int, String>> {
            if (ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return emptyList()
            return runCatching {
                ctx.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
                    .map { it.simSlotIndex + 1 to (it.displayName?.toString() ?: "SIM ${it.simSlotIndex + 1}") }.sortedBy { it.first }
            }.getOrDefault(emptyList())
        }

        fun version(ctx: Context): String = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"

        /** shared/PROTOCOL.md `status` message. */
        fun status(ctx: Context): JSONObject {
            val st = Store(ctx)
            fun pr(k: String) = progress(st, k).let { JSONArray().put(it.first).put(it.second) }
            return JSONObject().put("t", "status").put("ts", System.currentTimeMillis()).put("paused", st.paused)
                .put("version", version(ctx)).put("queue", Db.get(ctx).outboxSize())
                .put("battery_unrestricted", batteryUnrestricted(ctx))
                .put("sims", JSONArray(sims(ctx).map { JSONObject().put("slot", it.first).put("name", it.second) }))
                .put("backfill", JSONObject().put("sms", pr("sms")).put("calls", pr("calls")).put("contacts", pr("contacts"))
                    .put("complete", backfillComplete(ctx)))
                // PROTOCOL.md v3 additions
                .put("default_sim", st.defaultSim)
                .put("privacy", JSONObject().put("sms", st.syncAllowed("sms")).put("contacts", st.syncAllowed("contacts"))
                    .put("calls", st.syncAllowed("calls")))
        }
    }
}
