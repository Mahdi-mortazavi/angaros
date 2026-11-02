package app.phonebridge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val WHILE_SHOWN = SharingStarted.WhileSubscribed(5_000)

/** App shell: theme, language, paired/onboarded routing. */
class AppViewModel(repo: Repo) : ViewModel() {
    val settings: StateFlow<Settings> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, repo.settingsNow())
}

data class HomeUi(
    val conn: Conn = Conn.CHECKING, val fraction: Float = 1f, val relayMs: Long? = null, val health: Health = Health(),
    val counters: Counters? = null, val chain: ChainState = ChainState.Checking, val settings: Settings = Settings(),
)

class HomeViewModel(private val repo: Repo, private val fmt: () -> Fmt) : ViewModel() {
    private val recheck = MutableStateFlow(0)

    // Everything below runs only while the dashboard is on screen (WhileSubscribed): no relay pings in the background.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val relay = recheck.flatMapLatest { flow { emit(null); emitAll(repo.relayLatency()) } }
    private val counters = repo.smsChanges().map<Unit, Counters?> { repo.countersToday(fmt()) }.onStart { emit(null) }
    private val chain = flow { emit(ChainState.Checking); emit(repo.checkChain()) }

    val ui: StateFlow<HomeUi> = combine(repo.health(), repo.settings, relay, counters, chain) { h, s, r, c, ch ->
        val enabled = buildSet { if (s.syncSms) add("sms"); if (s.syncCalls) add("calls"); if (s.syncContacts) add("contacts") }
        HomeUi(connState(s.paused, r, h.complete, System.currentTimeMillis() - h.lastPoll < 120_000), backfillFraction(h.progress, enabled), r, h, c, ch, s)
    }.stateIn(viewModelScope, WHILE_SHOWN, HomeUi())

    fun recheckRelay() { recheck.value++ }
    fun syncNow() = viewModelScope.launch { repo.syncNow() }
    fun setPaused(on: Boolean) = viewModelScope.launch { repo.setPaused(on) }
    fun setDefaultSim(slot: Int) = viewModelScope.launch { repo.setDefaultSim(slot) }
}

data class FeedUi(val items: List<Msg> = emptyList(), val loading: Boolean = true, val end: Boolean = false,
                  val filter: Parse.Cls? = null, val permission: Boolean = true)

/**
 * Keyset pager over the SMS provider. With a class filter it keeps reading pages until [pageSize] matches are
 * found or [scanCap] rows were scanned, so rare classes still fill the screen without an unbounded scan.
 */
class FeedViewModel(
    private val load: suspend (after: FeedCursor?, limit: Int) -> List<Msg>,
    private val hasPermission: () -> Boolean,
    private val pageSize: Int = 40,
    private val scanCap: Int = 800,
) : ViewModel() {
    private val _ui = MutableStateFlow(FeedUi())
    val ui: StateFlow<FeedUi> = _ui.asStateFlow()
    private var cursor: FeedCursor? = null
    private var job: Job? = null

    init { reload() }

    private val format = MutableStateFlow<Pair<Fmt, String>?>(null)

    /** The UI's formatting (language, digits, calendar) and its "You" label; the rows are re-formatted when it changes. */
    fun setFormat(fmt: Fmt, you: String) { format.value = fmt to you }

    /**
     * [FeedUi.items] grouped by day and formatted on a worker thread: a row scrolling into view only lays out text.
     * ponytail: re-formats every loaded row per page (<= ~1k rows, a few ms off the main thread); cache by id if it grows.
     */
    val days: StateFlow<List<FeedDay>> = combine(_ui.map { it.items }.distinctUntilChanged(), format.filterNotNull()) { items, (fmt, you) ->
        feedDays(items, fmt, you)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, WHILE_SHOWN, emptyList())

    fun setFilter(f: Parse.Cls?) { if (f != _ui.value.filter) { _ui.update { it.copy(filter = f) }; reload() } }

    fun reload() {
        job?.cancel()
        cursor = null
        _ui.update { FeedUi(filter = it.filter, permission = hasPermission()) }
        loadMore(force = true)
    }

    fun loadMore(force: Boolean = false) {
        val s = _ui.value
        if (!force && (s.loading || s.end)) return
        _ui.update { it.copy(loading = true) }
        job = viewModelScope.launch {
            val filter = _ui.value.filter
            val found = mutableListOf<Msg>()
            var scanned = 0
            var end = false
            while (found.size < pageSize && scanned < scanCap) {
                val page = load(cursor, if (filter == null) pageSize else 200)
                if (page.isEmpty()) { end = true; break }
                cursor = page.last().let { FeedCursor(it.date, it.id) }
                scanned += page.size
                found += if (filter == null) page else page.filter { it.parsed.cls == filter }
            }
            _ui.update { it.copy(items = it.items + found, loading = false, end = end) }
        }
    }
}

data class SendsUi(val items: List<SendItem>? = null, val chain: ChainState = ChainState.Checking)

class SendsViewModel(private val repo: Repo) : ViewModel() {
    /** While shown: re-reads (and re-verifies) the audit log only when an entry was appended. */
    val ui: StateFlow<SendsUi> = flow {
        var seen = -1L
        while (true) {
            val n = repo.auditCount()
            if (n != seen) { emit(SendsUi(repo.sends(), repo.checkChain())); seen = n }
            delay(3_000)
        }
    }.stateIn(viewModelScope, WHILE_SHOWN, SendsUi())
}

class SettingsViewModel(private val repo: Repo) : ViewModel() {
    val settings: StateFlow<Settings> = repo.settings.stateIn(viewModelScope, WHILE_SHOWN, repo.settingsNow())
    fun setPaused(on: Boolean) = viewModelScope.launch { repo.setPaused(on) }
    fun resync() = viewModelScope.launch { repo.resyncAll() }
    fun setPrivacy(kind: String, on: Boolean) = viewModelScope.launch { repo.setPrivacy(kind, on) }
    fun setAllowCalls(on: Boolean) = viewModelScope.launch { repo.setAllowCalls(on) }
    fun setRelay(url: String?) = viewModelScope.launch { repo.setRelay(url) }
    fun setTheme(v: String) = viewModelScope.launch { repo.setTheme(v) }
    fun setDynamicColor(v: Boolean) = viewModelScope.launch { repo.setDynamicColor(v) }
    fun setPersianDigits(v: Boolean) = viewModelScope.launch { repo.setPersianDigits(v) }
    fun setJalali(v: Boolean) = viewModelScope.launch { repo.setJalali(v) }
    fun setLanguage(tag: String) = viewModelScope.launch { repo.setLanguage(tag) }
    fun scheduleUnpair(wipeAudit: Boolean) = repo.scheduleUnpair(wipeAudit)
    fun cancelUnpair() = repo.cancelUnpair()
    suspend fun diagnostics() = repo.diagnostics()
}

class OnboardingViewModel(private val repo: Repo) : ViewModel() {
    val step = MutableStateFlow(0)
    val error = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)

    fun pair(code: String, onDone: () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            runCatching { repo.pair(code) }
                .onSuccess { error.value = null; onDone() }
                .onFailure { error.value = it.message ?: it.javaClass.simpleName }
            busy.value = false
        }
    }
}
