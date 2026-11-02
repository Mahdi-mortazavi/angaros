package app.phonebridge.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.phonebridge.FeedRow
import app.phonebridge.FeedViewModel
import app.phonebridge.Fmt
import app.phonebridge.Msg
import app.phonebridge.Parse
import app.phonebridge.R
import app.phonebridge.isolate
import app.phonebridge.ltr
import app.phonebridge.Repo
import kotlinx.coroutines.launch

private val FILTERS = listOf(null, Parse.Cls.OTP, Parse.Cls.BANK, Parse.Cls.PERSONAL, Parse.Cls.DELIVERY, Parse.Cls.SERVICE, Parse.Cls.AD)

@Composable
fun FeedScreen(onOpen: (Long) -> Unit) {
    val ctx = LocalContext.current
    val repo = Repo.get(ctx)
    val vm: FeedViewModel = viewModel(factory = viewModelFactory {
        val app = ctx.applicationContext // the ViewModel outlives this Activity (e.g. a locale change)
        initializer { FeedViewModel(repo::messages, { app.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED }) }
    })
    val ui by vm.ui.collectAsStateWithLifecycle()
    val days by vm.days.collectAsStateWithLifecycle()
    val fmt = LocalFmt.current
    val you = stringResource(R.string.you)
    LaunchedEffect(fmt, you) { vm.setFormat(fmt, you) }
    val askSms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.reload() }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    // Rows (~85dp) are composed and measured up to ~4 ahead in the idle time between frames, not in the frame that
    // scrolls them in; the ones just passed stay ready for a reverse fling.
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    val list = rememberLazyListState(cacheWindow = LazyLayoutCacheWindow(ahead = 360.dp, behind = 180.dp))
    // 20 rows (~2 screens) early: the next page is usually loaded before a fling reaches the end.
    val nearEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 20 } ?: false } }
    // Keyed on the list size too: a filtered page that found few matches must keep scanning while the end is visible.
    LaunchedEffect(nearEnd, ui.items.size, ui.loading, ui.end) { if ((nearEnd || ui.items.isEmpty()) && !ui.loading && !ui.end) vm.loadMore() }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_activity)) }, scrollBehavior = scroll) },
        contentWindowInsets = TopAppBarDefaults.windowInsets,
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(FILTERS) { f ->
                    val sel = ui.filter == f
                    FilterChip(
                        selected = sel, onClick = { vm.setFilter(f) },
                        label = { Text(stringResource(f?.let { clsStyle(it).label } ?: R.string.filter_all)) },
                        leadingIcon = f?.let { { Sym(clsStyle(it).icon, null, Modifier.size(FilterChipDefaults.IconSize)) } },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            when {
                !ui.permission -> EmptyState(R.drawable.ic_sym_sms, stringResource(R.string.feed_perm_title), stringResource(R.string.feed_perm_body)) {
                    Button(onClick = { askSms.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS)) }) {
                        Text(stringResource(R.string.onb_perm_grant))
                    }
                }
                ui.items.isEmpty() && ui.end -> EmptyState(R.drawable.ic_sym_inbox, stringResource(R.string.feed_empty_title), stringResource(R.string.feed_empty_body))
                // The first page is formatted off the main thread; keep the skeleton until its rows arrive.
                days.isEmpty() -> SkeletonList()
                else -> PullToRefreshBox(isRefreshing = false, onRefresh = vm::reload, modifier = Modifier.fillMaxSize()) {
                    // Plain rows: no shared-element bounds or placement animation per item, they cost frames while flinging.
                    val bodyStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content)
                    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                        days.forEach { d ->
                            stickyHeader(key = "d${d.start}", contentType = "day") { DayHeader(d.label) }
                            items(d.rows, key = { it.msg.id }, contentType = { "msg" }) { r -> MsgRow(r, fmt, bodyStyle) { onOpen(r.msg.id) } }
                        }
                        // A plain spinner: the morphing LoadingIndicator rebuilds a path every frame on the main thread mid-fling.
                        if (ui.loading) item(contentType = "loading") { Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) { CircularProgressIndicator() } }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(label: String) {
    // Opaque so sticky rows scroll beneath it; a background modifier rather than a Surface layer.
    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp).semantics { heading() })
}

fun copyCode(ctx: Context, code: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("code", code))
}

/**
 * One message, laid out like a three-line ListItem (16dp sides, 12dp vertical, 16dp gaps) but as a single Row:
 * the material ListItem's slot layout and interaction states measured as a large part of each new row's frame.
 */
@Composable
private fun MsgRow(r: FeedRow, fmt: Fmt, bodyStyle: TextStyle, onClick: () -> Unit) {
    val m = r.msg
    val cs = MaterialTheme.colorScheme
    val st = clsStyle(m.parsed.cls)
    val (bg, fg) = clsColors(m.parsed.cls)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        ShapeAvatar(st.polygon, st.icon, bg, fg, letter = r.letter)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(r.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = cs.onSurface,
                style = if (!m.read && !m.outgoing) MaterialTheme.typography.titleMediumEmphasized else MaterialTheme.typography.titleMedium)
            // SMS text is in either language whatever the UI language: laid out by its own first letter (bodyStyle).
            Text(r.preview, maxLines = 2, overflow = TextOverflow.Ellipsis, style = bodyStyle, color = cs.onSurfaceVariant)
            val amount = m.parsed.amountRial
            if (r.code != null) CodeChip(m.parsed.otp!!, r.code)
            else if (amount != null) Amount(amount, m.parsed.direction, fmt)
        }
        Spacer(Modifier.width(16.dp))
        Text(r.time, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun CodeChip(code: String, shown: String) {
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val snack = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.copied)
    val label = stringResource(R.string.copy_code, code)
    AssistChip(
        onClick = { copyCode(ctx, code); haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            scope.launch { snack.currentSnackbarData?.dismiss(); snack.showSnackbar(copied, duration = SnackbarDuration.Short) } },
        label = { Text(shown, style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Ltr)) },
        leadingIcon = { Sym(R.drawable.ic_sym_content_copy, label, Modifier.size(AssistChipDefaults.IconSize)) },
    )
}

@Composable
fun Amount(rial: Long, direction: Int, fmt: Fmt) {
    val cs = MaterialTheme.colorScheme
    val sign = when { direction > 0 -> "+"; direction < 0 -> "−"; else -> "" }
    Text(isolate(sign + stringResource(R.string.amount_toman, fmt.toman(rial))), style = MaterialTheme.typography.titleSmall,
        color = if (direction > 0) cs.secondary else if (direction < 0) cs.error else cs.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp))
}

@Composable
fun MessageScreen(id: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = Repo.get(ctx)
    val m by produceState<Msg?>(null, id) { value = repo.message(id) }
    val fmt = LocalFmt.current
    val haptics = LocalHapticFeedback.current
    val snack = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.copied)
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.msg_detail)) }, navigationIcon = {
            IconButton(onClick = onBack) { Sym(R.drawable.ic_sym_arrow_back, stringResource(R.string.back)) } }) },
        contentWindowInsets = TopAppBarDefaults.windowInsets,
    ) { pad ->
        val msg = m ?: return@Scaffold Box(Modifier.padding(pad).fillMaxSize(), Alignment.Center) { LoadingIndicator() }
        val cs = MaterialTheme.colorScheme
        val st = clsStyle(msg.parsed.cls)
        val (bg, fg) = clsColors(msg.parsed.cls)
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Constrained {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = cs.surfaceContainerLow) {
                    Column(Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ShapeAvatar(st.polygon, st.icon, bg, fg, size = 52.dp)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(if (msg.outgoing) R.string.msg_to else R.string.msg_from), style = MaterialTheme.typography.labelMedium,
                                    color = cs.onSurfaceVariant)
                                Text(msg.name?.let { isolate(it) } ?: ltr(fmt.digits(msg.number)), style = MaterialTheme.typography.titleLarge)
                                if (msg.name != null) Text(ltr(fmt.digits(msg.number)), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            AssistChip(onClick = {}, label = { Text(stringResource(st.label)) },
                                leadingIcon = { Sym(st.icon, null, Modifier.size(AssistChipDefaults.IconSize)) })
                            Text(fmt.stamp(msg.date), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(16.dp))
                        SelectionContainer { Text(msg.body, style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content)) }
                        msg.parsed.otp?.let { code ->
                            Spacer(Modifier.height(20.dp))
                            FilledTonalButton(onClick = {
                                copyCode(ctx, code); haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                scope.launch { snack.showSnackbar(copied, duration = SnackbarDuration.Short) }
                            }) {
                                Sym(R.drawable.ic_sym_content_copy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.copy_code, fmt.digits(code)))
                            }
                        }
                        msg.parsed.amountRial?.let { Amount(it, msg.parsed.direction, fmt) }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.Top) {
                Sym(R.drawable.ic_sym_shield, null, Modifier.size(18.dp), cs.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.untrusted_note), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
    }
}
