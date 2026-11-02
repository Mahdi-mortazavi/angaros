package app.phonebridge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import org.json.JSONObject

/** Foreground service: long-polls the down mailbox, flushes the outbox and runs the periodic / on-change sync. */
class BridgeService : Service() {
    @Volatile private var running = false
    private lateinit var st: Store
    private val bg = HandlerThread("sync").apply { start() }
    private val h = Handler(bg.looper)
    private val syncTask = object : Runnable {
        override fun run() {
            h.removeCallbacks(this)
            val complete = Sync(this@BridgeService).run()
            // Backfill not finished: continue soon (slower while the relay is unreachable), else the regular 5-min delta.
            h.postDelayed(this, if (complete) SYNC_EVERY_MS else if (Db.get(this@BridgeService).outboxSize() > 0) 60_000 else 15_000)
        }
    }
    private val observer = object : ContentObserver(h) {
        override fun onChange(selfChange: Boolean) { h.removeCallbacks(syncTask); h.postDelayed(syncTask, 10_000) }
    }

    override fun onBind(i: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        st = Store(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("bridge", getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "bridge").setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle(getString(R.string.notif_title, getString(R.string.app_name))).setContentText(getString(R.string.notif_text))
            .setContentIntent(open).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (st.pairing == null || st.paused) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_RESYNC) h.post { Sync(this).run(full = true); h.post(syncTask) }
        if (!running) {
            running = true
            // On the sync thread, not here: onStartCommand runs on the main thread, and opening the app starts this
            // service, so the DB open, provider and binder calls below used to sit on the app's cold-start path.
            h.post {
                if (!running) return@post
                listOf(Uri.parse("content://sms"), ContactsContract.Contacts.CONTENT_URI, CallLog.Calls.CONTENT_URI).forEach {
                    runCatching { contentResolver.registerContentObserver(it, true, observer) }
                }
                Sms.sweep(this)
                runCatching { Outbox.add(this, Sync.status(this)) }.onFailure { st.error(it) }
                h.post(syncTask)
                // Checked under the lock onDestroy bumps pollGen with: a start racing a stop cannot leave a live poller.
                val g = synchronized(POLL_LOCK) { if (!running) return@post; ++pollGen }
                Thread({ pollLoop(g) }, "poll").start()
                Thread(::flushLoop, "outbox").start()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        synchronized(POLL_LOCK) { pollGen++ } // a poller still inside Api.down must drop what it fetched
        Outbox.kick()
        contentResolver.unregisterContentObserver(observer)
        h.removeCallbacksAndMessages(null)
        bg.quitSafely()
        super.onDestroy()
    }

    private fun flushLoop() {
        while (running) {
            val wait = runCatching { Outbox.flush(this) }.getOrDefault(30_000L)
            Outbox.await(if (wait == 0L) 60_000 else wait)
        }
    }

    /** Only the loop whose [gen] is current may move the cursor or execute; a stale one (old service instance) exits. */
    private fun pollLoop(gen: Int) {
        var failures = 0
        while (pollGen == gen) {
            val pr = st.pairing ?: break
            try {
                val (cursor, cmds) = Api(pr).down(st.long("downCursor"), 25)
                synchronized(POLL_LOCK) {
                    if (pollGen != gen) return // fetched by a stale loop: the current one re-fetches from the same cursor
                    // Cursor is committed synchronously BEFORE execution: a crash mid-send loses a command instead of
                    // sending twice. Duplicate ids are refused by the `sends` table as well.
                    st.p.edit().putLong("downCursor", cursor).putLong("lastPoll", System.currentTimeMillis()).commit()
                }
                failures = 0
                for (c in cmds) {
                    val r = if (st.paused) Sms.refuse(this, c, "phone is paused") else runCatching { execute(c) }.getOrElse { JSONObject().put("ok", false).put("error", it.message ?: it.javaClass.simpleName) }
                    if (r != null) Outbox.add(this, r.put("t", "result").put("id", c.optString("id")).put("op", c.optString("op")))
                }
            } catch (e: Exception) {
                st.error(e)
                failures++
                Thread.sleep(Backoff.delayMs(failures))
            }
        }
    }

    /** Returns the `result` body, or null when the result is produced later (send_sms). */
    private fun execute(c: JSONObject): JSONObject? {
        val ok = JSONObject().put("ok", true)
        fun fail(e: String) = JSONObject().put("ok", false).put("error", e)
        if (c.optString("op") != "rules_mirror" && System.currentTimeMillis() - c.optLong("ts") > CMD_MAX_AGE_MS)
            return if (c.optString("op") == "send_sms") Sms.expired(this, c) else fail("expired")
        return when (c.optString("op")) {
            "ping" -> Sync.status(this).also { it.remove("t"); it.remove("ts") }.put("ok", true)
            "resync" -> { h.post { Sync(this).run(full = true); h.post(syncTask) }; ok }
            "send_sms" -> { Sms.send(this, c, h); null }
            "rules_mirror" -> { st.rulesMirror = JSONObject().put("rules", c.optJSONArray("rules") ?: org.json.JSONArray())
                .put("ts", c.optLong("ts")).put("received", System.currentTimeMillis()); ok }
            "place_call" -> {
                if (!st.allowCalls) return fail("calls are disabled on the phone")
                if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return fail("CALL_PHONE not granted")
                getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", c.getString("number"), null), null)
                ok
            }
            else -> fail("unknown op")
        }
    }

    companion object {
        const val SYNC_EVERY_MS = 5 * 60_000L
        const val CMD_MAX_AGE_MS = 10 * 60_000L
        const val ACTION_RESYNC = "app.phonebridge.RESYNC"
        private val POLL_LOCK = Any()
        @Volatile private var pollGen = 0

        fun start(ctx: Context, action: String? = null) {
            val s = Store(ctx)
            // Background-start limits can refuse this; WorkManager / the next boot / app open retries.
            if (s.pairing != null && !s.paused) runCatching { ctx.startForegroundService(Intent(ctx, BridgeService::class.java).setAction(action)) }
                .onFailure { s.error(it) }
        }
    }
}

/**
 * send_sms: multipart send with per-part SENT and DELIVERY intents to [SmsStatusReceiver].
 * State lives in the `sends` table so callbacks still resolve if the process was restarted in between.
 */
object Sms {
    const val ACTION_SENT = "app.phonebridge.SMS_SENT"
    const val ACTION_DELIVERED = "app.phonebridge.SMS_DELIVERED"
    const val SENT_TIMEOUT_MS = 30_000L

    /** Kept in its own prefs file so unpair/re-pair (which clear "bridge") never restart the sequence. */
    @android.annotation.SuppressLint("ApplySharedPref") // must be on disk before the PendingIntent uses it
    private fun nextRequestCode(ctx: Context): Int = synchronized(this) {
        val p = ctx.applicationContext.getSharedPreferences("bridge_rc", Context.MODE_PRIVATE)
        val rc = (p.getInt("rc", 1000) + 1).let { if (it > 2_000_000_000) 1000 else it }
        p.edit().putInt("rc", rc).commit()
        rc
    }

    /** An explicit sim is honoured (and fails loudly if unusable); the owner's default only when it is usable now. */
    private fun slotOf(ctx: Context, c: JSONObject) = c.optInt("sim", 0).takeIf { it in 1..2 }
        ?: Store(ctx).defaultSim.takeIf { it in 1..2 && simActive(ctx, it) }

    private fun simActive(ctx: Context, slot: Int) = ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
        runCatching { ctx.getSystemService(SubscriptionManager::class.java).getActiveSubscriptionInfoForSimSlotIndex(slot - 1) != null }.getOrDefault(false)

    /** A command that arrived but must not run (paused): answered as failed; a send is audited like an expired one. */
    fun refuse(ctx: Context, c: JSONObject, why: String): JSONObject? {
        if (c.optString("op") != "send_sms") return JSONObject().put("ok", false).put("error", why)
        val audit = Db.get(ctx).appendAudit(c.optString("id"), agentOf(c), c.optString("number"), c.optString("text"), slotOf(ctx, c), "failed", why)
        return JSONObject().put("ok", false).put("status", "failed").put("error", why).put("audit", audit)
    }
    private fun agentOf(c: JSONObject) = c.optString("agent", "unknown").ifBlank { "unknown" }

    /** A send_sms older than 10 min: refused, audited as `expired`, reported as failed. */
    fun expired(ctx: Context, c: JSONObject): JSONObject {
        val id = c.optString("id")
        val audit = Db.get(ctx).appendAudit(id, agentOf(c), c.optString("number"), c.optString("text"), slotOf(ctx, c), "expired", "expired")
        return JSONObject().put("ok", false).put("status", "failed").put("error", "expired").put("audit", audit)
    }

    fun send(ctx: Context, c: JSONObject, h: Handler) {
        val d = Db.get(ctx)
        val db = d.writableDatabase
        val id = c.optString("id")
        val number = c.optString("number")
        val text = c.optString("text")
        val agent = agentOf(c)
        val slot = slotOf(ctx, c)
        val inserted = db.insertWithOnConflict("sends", null, ContentValues().apply {
            put("id", id); put("ts", System.currentTimeMillis()); put("agent", agent); put("number", number); put("text", text)
            put("sim", slot); put("parts", 0)
        }, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
        if (inserted == -1L) {
            // Same command id seen before: never send twice. Repeat the earlier answer if there is one.
            d.lastAuditFor(id)?.let { a ->
                Outbox.add(ctx, JSONObject().put("t", "result").put("id", id).put("op", "send_sms").put("ok", a.getString("result") == "sent")
                    .put("status", if (a.getString("result") == "sent") "sent" else "failed").put("error", a.opt("error")).put("audit", a))
            }
            return
        }
        try {
            require(number.isNotBlank() && text.isNotEmpty()) { "number and text are required" }
            if (ctx.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) error("SEND_SMS not granted")
            val sms = manager(ctx, slot)
            val parts = sms.divideMessage(text)
            db.execSQL("UPDATE sends SET parts=? WHERE id=?", arrayOf<Any>(parts.size, id))
            // The data URI makes every (send, part, kind) intent distinct for filterEquals, on top of unique request codes.
            fun pi(action: String, i: Int, flags: Int) = PendingIntent.getBroadcast(ctx, nextRequestCode(ctx),
                Intent(ctx, SmsStatusReceiver::class.java).setAction(action)
                    .setData(Uri.Builder().scheme("phonebridge").authority("sms").appendPath(id).appendPath(i.toString()).build())
                    .putExtra("id", id).putExtra("part", i), flags)
            val sent = ArrayList(parts.indices.map { pi(ACTION_SENT, it, PendingIntent.FLAG_IMMUTABLE) })
            // The delivery status is a fill-in extra ("pdu") that an immutable PendingIntent would strip. The intent is
            // explicit (our own non-exported receiver), so a mutable one is safe here.
            val dlvFlags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val delivered = ArrayList(parts.indices.map { pi(ACTION_DELIVERED, it, dlvFlags) })
            h.postDelayed({ finish(ctx, id, "no radio confirmation within 30 s") }, SENT_TIMEOUT_MS)
            if (parts.size == 1) sms.sendTextMessage(number, null, text, sent[0], delivered[0])
            else sms.sendMultipartTextMessage(number, null, parts, sent, delivered)
        } catch (e: Exception) {
            finish(ctx, id, e.message ?: e.javaClass.simpleName)
        }
    }

    @Suppress("DEPRECATION")
    private fun manager(ctx: Context, slot: Int?): SmsManager {
        val sub = slot?.let {
            if (ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) error("READ_PHONE_STATE needed to pick SIM $it")
            ctx.getSystemService(SubscriptionManager::class.java).getActiveSubscriptionInfoForSimSlotIndex(it - 1)?.subscriptionId
                ?: error("SIM $it is not active")
        }
        return if (Build.VERSION.SDK_INT >= 31) {
            val m = ctx.getSystemService(SmsManager::class.java)
            if (sub != null) m.createForSubscriptionId(sub) else m
        } else if (sub != null) SmsManager.getSmsManagerForSubscriptionId(sub) else SmsManager.getDefault()
    }

    fun onSent(ctx: Context, id: String, ok: Boolean, code: Int) {
        Db.get(ctx).writableDatabase.execSQL(
            "UPDATE sends SET sent_ok=sent_ok+?, sent_fail=sent_fail+?, err=COALESCE(err, ?) WHERE id=?",
            arrayOf<Any?>(if (ok) 1 else 0, if (ok) 0 else 1, if (ok) null else "radio error $code", id))
        finish(ctx, id, null, onlyWhenComplete = true)
    }

    /** Writes the audit entry and the `result` exactly once per send (all SENT callbacks, an error, or the timeout). */
    @Synchronized
    fun finish(ctx: Context, id: String, error: String?, onlyWhenComplete: Boolean = false) {
        val d = Db.get(ctx)
        val row = d.readableDatabase.rawQuery("SELECT agent, number, text, sim, parts, sent_ok, sent_fail, err, finalized FROM sends WHERE id=?", arrayOf(id))
            .use { if (it.moveToFirst()) Row(it.getString(0), it.getString(1), it.getString(2), if (it.isNull(3)) null else it.getInt(3),
                it.getInt(4), it.getInt(5), it.getInt(6), it.getString(7), it.getInt(8) == 1) else null } ?: return
        if (row.finalized) return
        if (onlyWhenComplete && (row.parts == 0 || row.ok + row.fail < row.parts)) return
        val err = row.err ?: error ?: if (row.ok < row.parts || row.parts == 0) "not sent" else null
        d.writableDatabase.execSQL("UPDATE sends SET finalized=1 WHERE id=?", arrayOf(id))
        val result = if (err == null) "sent" else "failed"
        val audit = d.appendAudit(id, row.agent, row.number, row.text, row.sim, result, err)
        Outbox.add(ctx, JSONObject().put("t", "result").put("id", id).put("op", "send_sms").put("ok", err == null)
            .put("status", result).put("error", err ?: JSONObject.NULL).put("audit", audit))
    }

    /** Delivery report for one part. Status lives in the PDU: GSM TP-Status <0x20 done, <0x40 pending, else failed. */
    @Synchronized
    fun onDelivered(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val pdu = intent.getByteArrayExtra("pdu")
        val format = intent.getStringExtra("format") ?: "3gpp"
        val ok: Boolean = if (pdu == null) true else {
            val s = runCatching { SmsMessage.createFromPdu(pdu, format).status }.getOrDefault(0)
            if (format == "3gpp") {
                if (s in 0x20..0x3F) return // still pending; a final report follows
                s < 0x20
            } else {
                val cls = (s shr 24) and 0x03 // 3GPP2 error class: 0 none, 2 temporary, 3 permanent
                if (cls == 2) return
                cls == 0
            }
        }
        val d = Db.get(ctx)
        d.writableDatabase.execSQL("UPDATE sends SET dlv_ok=dlv_ok+?, dlv_fail=dlv_fail+? WHERE id=? AND dlv_done=0",
            arrayOf<Any>(if (ok) 1 else 0, if (ok) 0 else 1, id))
        val row = d.readableDatabase.rawQuery("SELECT agent, number, text, sim, parts, dlv_ok, dlv_fail, dlv_done FROM sends WHERE id=?", arrayOf(id))
            .use { if (it.moveToFirst()) Row(it.getString(0), it.getString(1), it.getString(2), if (it.isNull(3)) null else it.getInt(3),
                it.getInt(4), it.getInt(5), it.getInt(6), null, it.getInt(7) == 1) else null } ?: return
        if (row.finalized || row.parts == 0 || row.ok + row.fail < row.parts) return
        d.writableDatabase.execSQL("UPDATE sends SET dlv_done=1 WHERE id=?", arrayOf(id))
        val delivered = row.fail == 0
        d.appendAudit(id, row.agent, row.number, row.text, row.sim, if (delivered) "delivered" else "delivery_failed",
            if (delivered) null else "${row.fail} of ${row.parts} parts not delivered")
        Outbox.add(ctx, JSONObject().put("t", "delivery").put("id", id).put("ok", delivered)
            .put("status", if (delivered) "delivered" else "failed").put("ts", System.currentTimeMillis()))
    }

    /** After a process restart the 30 s timers are gone: fail sends that never completed. */
    fun sweep(ctx: Context) {
        val cutoff = System.currentTimeMillis() - SENT_TIMEOUT_MS
        val ids = Db.get(ctx).readableDatabase.rawQuery("SELECT id FROM sends WHERE finalized=0 AND ts < ?", arrayOf(cutoff.toString()))
            .use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }
        ids.forEach { finish(ctx, it, "no radio confirmation within 30 s") }
    }

    private class Row(val agent: String, val number: String, val text: String, val sim: Int?, val parts: Int,
                      val ok: Int, val fail: Int, val err: String?, val finalized: Boolean)
}
