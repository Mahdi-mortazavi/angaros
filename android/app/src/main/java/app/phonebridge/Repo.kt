package app.phonebridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.ContactsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Owner-visible settings, read in one go so the UI recomposes once per change. */
data class Settings(
    val paired: Boolean = false, val onboarded: Boolean = false, val paused: Boolean = false,
    val theme: String = "system", val dynamicColor: Boolean = true, val language: String = Lang.EN,
    val persianDigits: Boolean? = null, val jalali: Boolean? = null,
    val defaultSim: Int = 0, val relayOverride: String? = null, val pairedRelay: String? = null,
    val syncSms: Boolean = true, val syncContacts: Boolean = true, val syncCalls: Boolean = true, val allowCalls: Boolean = false,
    /** Raw rules_mirror JSON (a String so Settings equality works and StateFlows de-duplicate). */
    val rules: String? = null,
)

data class Health(
    val lastPoll: Long = 0, val lastUpload: Long = 0, val lastSync: Long = 0, val queue: Long = 0,
    val battery: Boolean = true, val missingPerms: List<String> = emptyList(), val lastError: String? = null,
    val progress: Map<String, Pair<Long, Long>> = emptyMap(), val complete: Boolean = true,
    val sims: List<Pair<Int, String>> = emptyList(),
)

/** The app's single source of data for the UI. Every read happens off the main thread. */
class Repo private constructor(private val ctx: Context) {
    val st = Store(ctx)
    val db: Db get() = Db.get(ctx)
    private val io = Dispatchers.IO

    private fun Store.snapshot() = Settings(
        paired = pairing != null, onboarded = onboarded, paused = paused, theme = theme, dynamicColor = dynamicColor,
        language = language, persianDigits = persianDigits, jalali = jalali, defaultSim = defaultSim,
        relayOverride = relayOverride, pairedRelay = pairedRelay, syncSms = syncAllowed("sms"),
        syncContacts = syncAllowed("contacts"), syncCalls = syncAllowed("calls"), allowCalls = allowCalls, rules = p.getString("rulesMirror", null),
    )

    /** Emits on every preference change (sync cursors included, conflated). */
    val settings: Flow<Settings> = callbackFlow {
        trySend(st.snapshot())
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(st.snapshot()) }
        st.p.registerOnSharedPreferenceChangeListener(l)
        awaitClose { st.p.unregisterOnSharedPreferenceChangeListener(l) }
    }.conflate().flowOn(io)

    fun settingsNow(): Settings = st.snapshot()

    private fun granted(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    val neededPermissions: List<String> = buildList {
        addAll(listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_PHONE_STATE))
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun missingPermissions() = neededPermissions.filterNot(::granted)

    fun healthNow() = Health(
        lastPoll = st.long("lastPoll"), lastUpload = st.long("lastUpload"), lastSync = st.long("lastSync"),
        queue = db.outboxSize(), battery = Sync.batteryUnrestricted(ctx), missingPerms = missingPermissions(),
        lastError = st.str("lastError"),
        progress = listOf("sms", "calls", "contacts").associateWith { Sync.progress(st, it) },
        complete = Sync.backfillComplete(ctx), sims = Sync.sims(ctx),
    )

    /** Polled every 2 s while someone collects it (the dashboard). */
    fun health(): Flow<Health> = flow { while (true) { emit(healthNow()); delay(2_000) } }.flowOn(io)

    /** Round-trip ms to {relay}/v1/health every 30 s; -1 when unreachable. */
    fun relayLatency(): Flow<Long> = flow {
        while (true) {
            val relay = st.pairing?.worker
            if (relay != null) {
                val t0 = System.nanoTime()
                emit(if (Api.health(relay)) (System.nanoTime() - t0) / 1_000_000 else -1)
            }
            delay(30_000)
        }
    }.flowOn(io)

    suspend fun checkChain(): ChainState = withContext(io) {
        val broken = db.verifyAudit()
        val n = db.auditCount()
        when { broken != null -> ChainState.Broken(broken); n == 0L -> ChainState.Empty; else -> ChainState.Ok(n) }
    }

    suspend fun auditCount(): Long = withContext(io) { db.auditCount() }

    suspend fun sends(limit: Int = 600): List<SendItem> = withContext(io) { groupSends(db.auditPage(limit = limit)) }

    /** Emits Unit now and on every SMS provider change. */
    fun smsChanges(): Flow<Unit> = callbackFlow {
        val t = HandlerThread("sms-observer").apply { start() }
        val o = object : ContentObserver(Handler(t.looper)) { override fun onChange(selfChange: Boolean) { trySend(Unit) } }
        trySend(Unit)
        runCatching { ctx.contentResolver.registerContentObserver(Uri.parse("content://sms"), true, o) }
        awaitClose { ctx.contentResolver.unregisterContentObserver(o); t.quitSafely() }
    }.conflate()

    suspend fun countersToday(fmt: Fmt): Counters = withContext(io) {
        if (!granted(Manifest.permission.READ_SMS)) return@withContext Counters()
        val since = fmt.startOfDay(System.currentTimeMillis())
        ctx.contentResolver.query(Uri.parse("content://sms"), arrayOf("type", "body", "address"),
            "type IN (1,2) AND date >= ?", arrayOf(since.toString()), null)?.use { c ->
            countToday(generateSequence { if (c.moveToNext()) Triple(c.getInt(0), c.getString(1) ?: "", c.getString(2) ?: "") else null })
        } ?: Counters()
    }

    private val names = ConcurrentHashMap<String, String>()
    private val NO_NAME = "\u0000"

    fun contactName(number: String): String? {
        if (number.isBlank() || !granted(Manifest.permission.READ_CONTACTS)) return null
        names[number]?.let { return it.takeIf { n -> n != NO_NAME } }
        val name = runCatching {
            ctx.contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        names[number] = name ?: NO_NAME
        return name
    }

    /** Keyset page of inbox+sent SMS strictly after [after] in (date DESC, _id DESC) order. */
    suspend fun messages(after: FeedCursor?, limit: Int): List<Msg> = withContext(io) {
        if (!granted(Manifest.permission.READ_SMS)) return@withContext emptyList()
        ctx.contentResolver.query(Uri.parse("content://sms"), arrayOf("_id", "type", "address", "body", "date", "read"),
            if (after == null) "type IN (1,2)" else "type IN (1,2) AND (date < ? OR (date = ? AND _id < ?))",
            after?.let { arrayOf(it.date.toString(), it.date.toString(), it.id.toString()) }, "date DESC, _id DESC")?.use { c ->
            generateSequence { if (c.moveToNext()) c else null }.take(limit).map {
                val number = it.getString(2) ?: ""
                val body = it.getString(3) ?: ""
                val name = contactName(number)
                Msg(it.getLong(0), it.getInt(1) == 2, number, name, body, it.getLong(4), it.getInt(5) == 1,
                    Parse.classify(body, number, knownContact = name != null))
            }.toList()
        } ?: emptyList()
    }

    suspend fun message(id: Long): Msg? = withContext(io) {
        if (!granted(Manifest.permission.READ_SMS)) return@withContext null
        ctx.contentResolver.query(Uri.parse("content://sms"), arrayOf("_id", "type", "address", "body", "date", "read"),
            "_id = ?", arrayOf(id.toString()), null)?.use {
            if (!it.moveToFirst()) null else {
                val number = it.getString(2) ?: ""
                val name = contactName(number)
                Msg(it.getLong(0), it.getInt(1) == 2, number, name, it.getString(3) ?: "", it.getLong(4), it.getInt(5) == 1,
                    Parse.classify(it.getString(3) ?: "", number, name != null))
            }
        }
    }

    // ---------------- actions ----------------

    /** Re-announce the status (default SIM, privacy, pause) so the PC sees the change within seconds. */
    private suspend fun pushStatus() = withContext(io) {
        if (st.pairing == null) return@withContext
        runCatching { Sync.maybeStatus(ctx); Outbox.kick() }
    }

    private val pauseLock = kotlinx.coroutines.sync.Mutex()

    /** Serialised, so a quick pause + undo can never end with the service stopped while the UI says running. */
    suspend fun setPaused(on: Boolean) = withContext(io) {
        pauseLock.withLock {
            st.paused = on
            if (on) ctx.stopService(Intent(ctx, BridgeService::class.java))
            else { Outbox.resetBackoff(); BridgeService.start(ctx) }
        }
    }

    /** Outlives any screen: a delayed unpair must still happen if the user leaves Settings during the undo window. */
    private val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + io)
    private var pendingUnpair: kotlinx.coroutines.Job? = null

    /** Pauses now (nothing more leaves the phone), wipes after [graceMs] unless [cancelUnpair] runs first. */
    fun scheduleUnpair(wipeAudit: Boolean, graceMs: Long = 6_000) {
        pendingUnpair?.cancel()
        pendingUnpair = appScope.launch {
            val wasPaused = st.paused
            setPaused(true)
            try { kotlinx.coroutines.delay(graceMs) } catch (e: kotlinx.coroutines.CancellationException) {
                if (!wasPaused) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { setPaused(false) }
                throw e
            }
            unpair(wipeAudit)
        }
    }

    fun cancelUnpair() { pendingUnpair?.cancel(); pendingUnpair = null }

    suspend fun syncNow() = withContext(io) { Outbox.resetBackoff(); BridgeService.start(ctx); BridgeWorker.flushSoon(ctx) }

    suspend fun resyncAll() = withContext(io) { Outbox.resetBackoff(); BridgeService.start(ctx, BridgeService.ACTION_RESYNC) }

    suspend fun setDefaultSim(slot: Int) { withContext(io) { st.defaultSim = slot }; pushStatus() }

    suspend fun setPrivacy(kind: String, on: Boolean) {
        withContext(io) { st.setSyncAllowed(kind, on); if (!on) db.outboxDropKind(kind) }
        pushStatus()
        if (on) syncNow()
    }

    suspend fun setAllowCalls(on: Boolean) = withContext(io) { st.allowCalls = on }
    suspend fun setRelay(url: String?) = withContext(io) { st.relayOverride = url; Outbox.resetBackoff() }
    suspend fun setTheme(v: String) = withContext(io) { st.theme = v }
    suspend fun setDynamicColor(v: Boolean) = withContext(io) { st.dynamicColor = v }
    suspend fun setPersianDigits(v: Boolean) = withContext(io) { st.persianDigits = v }
    suspend fun setJalali(v: Boolean) = withContext(io) { st.jalali = v }
    suspend fun setOnboarded() = withContext(io) { st.onboarded = true }

    /** "en" | "fa", applied at once: API 33+ through LocaleManager; older releases need Activity.recreate() too. */
    suspend fun setLanguage(tag: String) {
        withContext(io) { st.p.edit().putString("language", Lang.normalize(tag)).commit() }
        Lang.apply(ctx, tag)
    }

    /** Validates and stores a pb1: code, then starts the bridge. Throws with a readable message on a bad code. */
    suspend fun pair(code: String) = withContext(io) {
        Pairing.parse(code)
        ctx.stopService(Intent(ctx, BridgeService::class.java))
        st.savePairing(code)
        st.onboarded = true
        db.wipe(alsoAudit = false) // the old mailbox's queue must not leak into the new one
        BridgeWorker.schedule(ctx)
        BridgeService.start(ctx)
    }

    suspend fun unpair(wipeAudit: Boolean) = withContext(io) {
        ctx.stopService(Intent(ctx, BridgeService::class.java))
        BridgeWorker.cancel(ctx)
        db.wipe(wipeAudit)
        st.wipe()
    }

    /** Health and settings only: no message text, numbers, pairing token, key or relay path. */
    suspend fun diagnostics(): String = withContext(io) {
        val h = healthNow()
        val s = st.snapshot()
        JSONObject()
            .put("app", Sync.version(ctx)).put("android", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
            .put("device", Build.MANUFACTURER + " " + Build.MODEL).put("generated", System.currentTimeMillis())
            .put("paired", s.paired).put("paused", s.paused)
            .put("relay_host", (s.relayOverride ?: s.pairedRelay)?.let { Uri.parse(it).host })
            .put("relay_custom", s.relayOverride != null)
            .put("last_poll", h.lastPoll).put("last_upload", h.lastUpload).put("last_sync", h.lastSync)
            .put("queue", h.queue).put("battery_unrestricted", h.battery)
            .put("missing_permissions", JSONArray(h.missingPerms.map { it.substringAfterLast('.') }))
            .put("backfill", JSONObject().apply { h.progress.forEach { (k, v) -> put(k, JSONArray().put(v.first).put(v.second)) } }
                .put("complete", h.complete))
            .put("privacy", JSONObject().put("sms", s.syncSms).put("contacts", s.syncContacts).put("calls", s.syncCalls))
            .put("default_sim", s.defaultSim).put("sim_count", h.sims.size)
            .put("audit_entries", db.auditCount()).put("audit_intact", db.verifyAudit() == null)
            .put("rules_mirrored", parseRules(s.rules?.let { runCatching { JSONObject(it) }.getOrNull() }).second.size)
            .put("last_error", h.lastError)
            .toString(2)
    }

    companion object {
        @android.annotation.SuppressLint("StaticFieldLeak") // holds the application context only (see get())
        @Volatile private var inst: Repo? = null
        fun get(ctx: Context): Repo = inst ?: synchronized(this) { inst ?: Repo(ctx.applicationContext).also { inst = it } }
    }
}
