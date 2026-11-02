package app.phonebridge

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Settings and sync cursors live in one SharedPreferences file. */
class Store(ctx: Context) {
    val p: SharedPreferences = ctx.applicationContext.getSharedPreferences("bridge", Context.MODE_PRIVATE)

    /** The pairing, with the relay URL replaced by the custom one from Settings when that is set. */
    val pairing: Pairing?
        get() {
            val pr = p.getString("pairing", null)?.let { runCatching { Pairing.parse(it) }.getOrNull() } ?: return null
            val custom = relayOverride
            return if (custom.isNullOrBlank()) pr else Pairing(custom, pr.token, pr.key)
        }
    val pairedRelay: String? get() = p.getString("pairing", null)?.let { runCatching { Pairing.parse(it).worker }.getOrNull() }

    var paused: Boolean
        get() = p.getBoolean("paused", false)
        set(v) = p.edit().putBoolean("paused", v).apply()
    var allowCalls: Boolean
        get() = p.getBoolean("allowCalls", false)
        set(v) = p.edit().putBoolean("allowCalls", v).apply()
    /** 0 = the phone's default SMS SIM, else slot 1|2. */
    var defaultSim: Int
        get() = p.getInt("defaultSim", 0)
        set(v) = p.edit().putInt("defaultSim", v).apply()
    var relayOverride: String?
        get() = p.getString("relay", null)
        set(v) = p.edit().putString("relay", v?.trim()?.trimEnd('/')?.ifBlank { null }).apply()

    /** Privacy: which categories may leave the phone. A disabled kind is neither read nor uploaded. */
    fun syncAllowed(kind: String) = p.getBoolean("sync.$kind", true)
    fun setSyncAllowed(kind: String, on: Boolean) = p.edit().putBoolean("sync.$kind", on).apply()

    /** "system" | "light" | "dark". */
    var theme: String
        get() = p.getString("theme", "system") ?: "system"
        set(v) = p.edit().putString("theme", v).apply()
    var dynamicColor: Boolean
        get() = p.getBoolean("dynamicColor", true)
        set(v) = p.edit().putBoolean("dynamicColor", v).apply()
    /** "en" (the default, whatever the system locale) or "fa"; see [Lang]. */
    var language: String
        get() = Lang.normalize(p.getString("language", null))
        set(v) = p.edit().putString("language", Lang.normalize(v)).apply()
    /** null = follow the UI language (Persian digits and Jalali dates in Persian). */
    var persianDigits: Boolean?
        get() = if (p.contains("persianDigits")) p.getBoolean("persianDigits", true) else null
        set(v) = p.edit().apply { if (v == null) remove("persianDigits") else putBoolean("persianDigits", v) }.apply()
    var jalali: Boolean?
        get() = if (p.contains("jalali")) p.getBoolean("jalali", true) else null
        set(v) = p.edit().apply { if (v == null) remove("jalali") else putBoolean("jalali", v) }.apply()
    var onboarded: Boolean
        get() = p.getBoolean("onboarded", false) || p.contains("pairing")
        set(v) = p.edit().putBoolean("onboarded", v).apply()

    /** Read-only mirror of the PC rules (PROTOCOL.md v3 `rules_mirror`), stored as received. */
    var rulesMirror: JSONObject?
        get() = p.getString("rulesMirror", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        set(v) = p.edit().putString("rulesMirror", v?.toString()).apply()

    fun long(k: String, def: Long = 0) = p.getLong(k, def)
    fun setLong(k: String, v: Long) = p.edit().putLong(k, v).apply()
    fun str(k: String) = p.getString(k, null)
    fun setStr(k: String, v: String?) = p.edit().putString(k, v).apply()

    fun savePairing(code: String) {
        Pairing.parse(code)
        // New pairing = new mailbox: forget every cursor so the backfill starts over. Settings survive.
        val keep = p.all.filterKeys { it in SETTINGS }
        p.edit().clear().apply {
            keep.forEach { (k, v) -> when (v) { is Boolean -> putBoolean(k, v); is Int -> putInt(k, v); is String -> putString(k, v) } }
        }.putString("pairing", code.trim()).apply()
    }

    /** Unpair: forget pairing, cursors, the rules mirror and the sync/relay settings; appearance stays. */
    @android.annotation.SuppressLint("ApplySharedPref") // the pairing must be gone before the UI moves on
    fun wipe() {
        val keep = p.all.filterKeys { it in APPEARANCE }
        p.edit().clear().apply {
            keep.forEach { (k, v) -> when (v) { is Boolean -> putBoolean(k, v); is Int -> putInt(k, v); is String -> putString(k, v) } }
        }.commit()
    }

    /** Forget sync cursors: the next sync re-uploads everything (newest first). */
    fun resetSync() {
        val e = p.edit()
        p.all.keys.filter { k -> CURSOR_PREFIXES.any { k.startsWith(it) } }.forEach { e.remove(it) }
        e.apply()
    }

    fun error(e: Throwable?) = setStr("lastError", e?.let {
        "${java.text.DateFormat.getTimeInstance().format(System.currentTimeMillis())} ${it.message ?: it.javaClass.simpleName}"
    })

    companion object {
        /** Owner preferences that survive an unpair (privacy and SIM choice are not tied to a pairing). */
        val APPEARANCE = setOf("theme", "dynamicColor", "language", "persianDigits", "jalali", "onboarded",
            "sync.sms", "sync.contacts", "sync.calls", "defaultSim")
        val SETTINGS = setOf("allowCalls", "defaultSim", "relay", "sync.sms", "sync.contacts", "sync.calls") + APPEARANCE
        val CURSOR_PREFIXES = listOf("sms.", "calls.", "contacts.", "unread")
    }
}

/** App database: the outbox, the hash-chained send audit log and in-flight sends. Plain android.database.sqlite. */
class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "bridge.db", null, 2) {
    init { setWriteAheadLoggingEnabled(true) } // the service writes while the UI reads

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE outbox (id INTEGER PRIMARY KEY AUTOINCREMENT, body TEXT NOT NULL)")
        createAudit(db)
        db.execSQL("""CREATE TABLE sends (id TEXT PRIMARY KEY, ts INTEGER, agent TEXT, number TEXT, text TEXT, sim INTEGER,
            parts INTEGER, sent_ok INTEGER DEFAULT 0, sent_fail INTEGER DEFAULT 0, err TEXT, finalized INTEGER DEFAULT 0,
            dlv_ok INTEGER DEFAULT 0, dlv_fail INTEGER DEFAULT 0, dlv_done INTEGER DEFAULT 0)""")
        createIndexes(db)
    }

    /** v2: lookups by command id (duplicate send answer, send detail) and the stuck-send sweep. */
    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS audit_by_id ON audit(id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS sends_open ON sends(finalized, ts)")
    }

    private fun createAudit(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE audit (seq INTEGER PRIMARY KEY, ts INTEGER NOT NULL, id TEXT, agent TEXT, number TEXT,
            text TEXT, sim INTEGER, result TEXT NOT NULL, error TEXT, prev TEXT NOT NULL, hash TEXT NOT NULL)""")
        // Append-only: the app itself cannot rewrite history. Only an explicit user wipe drops the whole table.
        db.execSQL("CREATE TRIGGER audit_no_update BEFORE UPDATE ON audit BEGIN SELECT RAISE(ABORT, 'audit log is append-only'); END")
        db.execSQL("CREATE TRIGGER audit_no_delete BEFORE DELETE ON audit BEGIN SELECT RAISE(ABORT, 'audit log is append-only'); END")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 2) createIndexes(db)
    }

    // ---- outbox ----
    /** Throws on failure, so callers never advance a sync cursor past rows that did not reach the outbox. */
    fun enqueue(body: String) { writableDatabase.insertOrThrow("outbox", null, ContentValues().apply { put("body", body) }) }
    fun auditCount(): Long = android.database.DatabaseUtils.queryNumEntries(readableDatabase, "audit")
    /** Privacy: drop queued uploads of [kind] (sms|calls|contacts). Every producer writes "t" as the first key. */
    fun outboxDropKind(kind: String): Int = writableDatabase.delete("outbox", "body LIKE ?", arrayOf("{\"t\":\"$kind\",%"))

    fun outboxSize(): Long = android.database.DatabaseUtils.queryNumEntries(readableDatabase, "outbox")
    fun outboxHead(): Pair<Long, String>? = readableDatabase.rawQuery("SELECT id, body FROM outbox ORDER BY id LIMIT 1", null)
        .use { if (it.moveToFirst()) it.getLong(0) to it.getString(1) else null }
    fun outboxDelete(id: Long) { writableDatabase.delete("outbox", "id=?", arrayOf(id.toString())) }

    // ---- audit ----
    /** Appends one chained entry and returns it as the JSON that goes into `result.audit`. */
    @Synchronized
    fun appendAudit(id: String, agent: String, number: String, text: String, sim: Int?, result: String, error: String?): JSONObject {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val (lastSeq, prev) = db.rawQuery("SELECT seq, hash FROM audit ORDER BY seq DESC LIMIT 1", null)
                .use { if (it.moveToFirst()) it.getLong(0) to it.getString(1) else 0L to AuditHash.GENESIS }
            val seq = lastSeq + 1
            val ts = System.currentTimeMillis()
            val hash = AuditHash.of(prev, listOf(seq, ts, id, agent, number, text, sim, result, error))
            db.insertOrThrow("audit", null, ContentValues().apply {
                put("seq", seq); put("ts", ts); put("id", id); put("agent", agent); put("number", number); put("text", text)
                put("sim", sim); put("result", result); put("error", error); put("prev", prev); put("hash", hash)
            })
            db.setTransactionSuccessful()
            return JSONObject().put("seq", seq).put("ts", ts).put("id", id).put("agent", agent).put("number", number)
                .put("text", text).put("sim", sim ?: JSONObject.NULL).put("result", result).put("error", error ?: JSONObject.NULL)
                .put("prev", prev).put("hash", hash)
        } finally {
            db.endTransaction()
        }
    }

    /** Latest entry for a command id, in the result.audit shape (used to answer a duplicate send_sms). */
    fun lastAuditFor(id: String): JSONObject? = readableDatabase.rawQuery(
        "SELECT seq, ts, id, agent, number, text, sim, result, error, prev, hash FROM audit WHERE id=? AND result IN ('sent','failed','expired') ORDER BY seq DESC LIMIT 1",
        arrayOf(id)).use { c ->
        if (!c.moveToFirst()) null else JSONObject().put("seq", c.getLong(0)).put("ts", c.getLong(1)).put("id", c.getString(2))
            .put("agent", c.getString(3)).put("number", c.getString(4)).put("text", c.getString(5))
            .put("sim", if (c.isNull(6)) JSONObject.NULL else c.getInt(6)).put("result", c.getString(7))
            .put("error", c.getString(8) ?: JSONObject.NULL).put("prev", c.getString(9)).put("hash", c.getString(10))
    }

    /** Newest first, keyset-paged: entries with seq < [beforeSeq]. [id] restricts to one command. */
    fun auditPage(beforeSeq: Long = Long.MAX_VALUE, limit: Int = 100, id: String? = null): List<AuditRow> = readableDatabase.rawQuery(
        "SELECT seq, ts, id, agent, number, text, sim, result, error, hash FROM audit WHERE seq < ?" +
            (if (id != null) " AND id = ?" else "") + " ORDER BY seq DESC LIMIT $limit",
        listOfNotNull(beforeSeq.toString(), id).toTypedArray()).use { c ->
        generateSequence { if (c.moveToNext()) AuditRow(c.getLong(0), c.getLong(1), c.getString(2) ?: "", c.getString(3) ?: "",
            c.getString(4) ?: "", c.getString(5) ?: "", if (c.isNull(6)) null else c.getInt(6), c.getString(7), c.getString(8),
            c.getString(9)) else null }.toList()
    }

    /** Recomputes the whole chain; returns the first broken seq, or null when intact. */
    fun verifyAudit(): Long? = readableDatabase.rawQuery(
        "SELECT seq, ts, id, agent, number, text, sim, result, error, prev, hash FROM audit ORDER BY seq", null).use { c ->
        var prev = AuditHash.GENESIS
        while (c.moveToNext()) {
            val sim = if (c.isNull(6)) null else c.getLong(6)
            val h = AuditHash.of(prev, listOf(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4),
                c.getString(5), sim, c.getString(7), c.getString(8)))
            if (c.getString(9) != prev || c.getString(10) != h) return c.getLong(0)
            prev = h
        }
        null
    }

    /** Unpair: drop outbox and in-flight sends; the audit log only when the user explicitly asked for it. */
    fun wipe(alsoAudit: Boolean) {
        val db = writableDatabase
        db.delete("outbox", null, null)
        db.delete("sends", null, null)
        if (alsoAudit) { db.execSQL("DROP TABLE audit"); createAudit(db); createIndexes(db) }
    }

    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) { inst ?: Db(ctx.applicationContext).also { inst = it } }
    }
}

class HttpError(val code: Int, msg: String) : IOException(msg)

/** Relay mailbox client; every payload is sealed before it leaves the phone. */
class Api(private val pr: Pairing) {
    private fun call(method: String, path: String, body: String?, timeoutMs: Int): JSONObject {
        val c = URL(pr.worker + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000
        c.readTimeout = timeoutMs
        c.setRequestProperty("Authorization", "Bearer " + pr.token)
        c.setRequestProperty("Content-Type", "application/json")
        try {
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code >= 400) throw HttpError(code, "relay HTTP $code ${text.take(100)}")
            return JSONObject(text)
        } finally {
            c.disconnect()
        }
    }

    fun up(msg: String) {
        val blob = Crypto.seal(pr.key, pr.device + "|up", msg.toByteArray())
        call("POST", "/v1/up", JSONObject().put("blob", blob).toString(), 30_000)
    }

    /** Long-polls the down mailbox. Returns (cursor, decrypted commands); undecryptable items are dropped. */
    fun down(after: Long, waitS: Int): Pair<Long, List<JSONObject>> {
        val r = call("GET", "/v1/down?after=$after&wait=$waitS&limit=50", null, (waitS + 20) * 1000)
        val items = r.getJSONArray("items")
        val cmds = (0 until items.length()).mapNotNull {
            runCatching { JSONObject(String(Crypto.open(pr.key, pr.device + "|down", items.getJSONObject(it).getString("blob")))) }.getOrNull()
        }
        return r.getLong("cursor") to cmds
    }

    companion object {
        /** GET {relay}/v1/health (no auth). */
        fun health(relay: String): Boolean = runCatching {
            val c = URL(relay.trimEnd('/') + "/v1/health").openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 10_000
            try { c.responseCode == 200 && JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optBoolean("ok") }
            finally { c.disconnect() }
        }.getOrDefault(false)
    }
}

/** Every `up` message goes through the persistent outbox, flushed in order with exponential backoff. */
object Outbox {
    private val lock = java.util.concurrent.locks.ReentrantLock()
    private val signal = lock.newCondition()
    @Volatile private var failures = 0
    @Volatile private var nextTry = 0L

    fun add(ctx: Context, msg: JSONObject) {
        Db.get(ctx).enqueue(msg.toString())
        kick()
    }

    @Volatile private var pending = false

    /** A kick that lands while nobody waits is remembered, so the next await() returns at once. */
    fun kick() { lock.lock(); try { pending = true; signal.signalAll() } finally { lock.unlock() } }

    /** Blocks up to [ms] or until something is enqueued. */
    fun await(ms: Long) {
        if (ms <= 0) return
        lock.lock(); try { if (!pending) signal.await(ms, java.util.concurrent.TimeUnit.MILLISECONDS); pending = false } finally { lock.unlock() }
    }

    /** Sends queued messages oldest-first. Returns 0 when the queue is empty, else ms until the next retry. */
    @Synchronized
    fun flush(ctx: Context): Long {
        val st = Store(ctx)
        val db = Db.get(ctx)
        val wait = nextTry - System.currentTimeMillis()
        if (wait > 0) return wait
        val pr = st.pairing ?: return 60_000
        if (st.paused) return 60_000
        val api = Api(pr)
        while (true) {
            // Pause, unpair or re-pair can happen mid-flush: never upload another row once the bridge is paused or
            // the pairing (mailbox + key) is no longer the one this flush started with.
            val cur = st.pairing
            if (st.paused || cur == null || cur.token != pr.token || cur.worker != pr.worker) return 60_000
            val (id, body) = db.outboxHead() ?: return 0
            try {
                api.up(body)
                db.outboxDelete(id)
                failures = 0; nextTry = 0
                st.setLong("lastUpload", System.currentTimeMillis())
            } catch (e: Exception) {
                st.error(e)
                if (e is HttpError && e.code in 400..499 && e.code !in RETRY_4XX) {
                    db.outboxDelete(id) // the relay will never accept this one (e.g. 413); do not block the queue
                    continue
                }
                failures++
                val d = Backoff.delayMs(failures)
                nextTry = System.currentTimeMillis() + d
                return d
            }
        }
    }

    /** Lets the next flush go immediately (user pressed resync, network came back...). */
    fun resetBackoff() { failures = 0; nextTry = 0; kick() }

    private val RETRY_4XX = setOf(401, 403, 408, 429)
}
