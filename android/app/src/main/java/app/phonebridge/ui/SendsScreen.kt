package app.phonebridge.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.phonebridge.ChainState
import app.phonebridge.Fmt
import app.phonebridge.R
import app.phonebridge.isolate
import app.phonebridge.ltr
import app.phonebridge.Repo
import app.phonebridge.SendItem
import app.phonebridge.SendStatus
import app.phonebridge.SendsViewModel

@Composable
private fun sendsVm(): SendsViewModel {
    val ctx = LocalContext.current
    return viewModel(factory = viewModelFactory { initializer { SendsViewModel(Repo.get(ctx)) } })
}

@Composable
fun statusLabel(s: SendStatus) = stringResource(when (s) {
    SendStatus.PENDING -> R.string.result_pending; SendStatus.SENT -> R.string.result_sent
    SendStatus.DELIVERED -> R.string.result_delivered; SendStatus.NOT_DELIVERED -> R.string.result_delivery_failed
    SendStatus.FAILED -> R.string.result_failed; SendStatus.EXPIRED -> R.string.result_expired
})

@Composable
private fun statusLook(s: SendStatus): Triple<Int, Color, Color> {
    val cs = MaterialTheme.colorScheme
    return when (s) {
        SendStatus.DELIVERED -> Triple(R.drawable.ic_sym_done_all, cs.secondaryContainer, cs.onSecondaryContainer)
        SendStatus.SENT -> Triple(R.drawable.ic_sym_done, cs.primaryContainer, cs.onPrimaryContainer)
        SendStatus.PENDING -> Triple(R.drawable.ic_sym_schedule, cs.surfaceContainerHighest, cs.onSurfaceVariant)
        SendStatus.EXPIRED -> Triple(R.drawable.ic_sym_history, cs.surfaceContainerHighest, cs.onSurfaceVariant)
        SendStatus.NOT_DELIVERED, SendStatus.FAILED -> Triple(R.drawable.ic_sym_error, cs.errorContainer, cs.onErrorContainer)
    }
}

@Composable
fun SendsScreen(onOpen: (String) -> Unit) {
    val vm = sendsVm()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val fmt = LocalFmt.current
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.sends_title)) }) }, contentWindowInsets = TopAppBarDefaults.windowInsets) { pad ->
        val items = ui.items
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Constrained { ChainBanner(ui.chain, fmt) } }
            when {
                items == null -> item { SkeletonList(5) }
                items.isEmpty() -> item { EmptyState(R.drawable.ic_sym_outgoing_mail, stringResource(R.string.sends_empty_title), stringResource(R.string.sends_empty_body)) }
                else -> items(items, key = { it.id }) { s -> Constrained { SendCard(s, fmt, Modifier.animateItem()) { onOpen(s.id) } } }
            }
        }
    }
}

@Composable
private fun ChainBanner(chain: ChainState, fmt: Fmt) {
    val cs = MaterialTheme.colorScheme
    val broken = chain is ChainState.Broken
    Surface(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp), shape = RoundedCornerShape(28.dp),
        color = if (broken) cs.errorContainer else cs.tertiaryContainer) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                MorphShape(if (broken) MaterialShapes.Clover4Leaf else MaterialShapes.Cookie12Sided,
                    if (broken) cs.error else cs.tertiary, Modifier.size(52.dp))
                Sym(if (broken) R.drawable.ic_sym_gpp_bad else R.drawable.ic_sym_verified, null, Modifier.size(26.dp),
                    if (broken) cs.onError else cs.onTertiary)
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(when (chain) {
                    ChainState.Checking -> stringResource(R.string.chain_checking)
                    ChainState.Empty -> stringResource(R.string.chain_empty)
                    is ChainState.Ok -> stringResource(R.string.chain_ok, fmt.num(chain.entries))
                    is ChainState.Broken -> stringResource(R.string.chain_broken, fmt.num(chain.atSeq))
                }, style = MaterialTheme.typography.titleMedium, color = if (broken) cs.onErrorContainer else cs.onTertiaryContainer)
                Text(stringResource(R.string.chain_ok_body), style = MaterialTheme.typography.bodySmall,
                    color = if (broken) cs.onErrorContainer else cs.onTertiaryContainer)
            }
        }
    }
}

@Composable
private fun SendCard(s: SendItem, fmt: Fmt, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val name = app.phonebridge.Repo.get(LocalContext.current).let { r -> androidx.compose.runtime.produceState<String?>(null, s.number) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { r.contactName(s.number) } }.value }
    val (icon, bg, fg) = statusLook(s.status)
    val label = statusLabel(s.status)
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth().sharedCard("send-${s.id}"), shape = RoundedCornerShape(24.dp), color = cs.surfaceContainerLow) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name?.let { isolate(it) } ?: ltr(fmt.digits(s.number)), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.by_agent, s.agent) + (s.sim?.let { "  ·  " + stringResource(R.string.sim_slot, fmt.digits(it.toString())) } ?: ""),
                        style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                }
                Text(fmt.stamp(s.ts), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            Text(s.text, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content), maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(50), color = bg) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.CenterVertically) {
                        Sym(icon, null, Modifier.size(16.dp), fg); Spacer(Modifier.width(6.dp))
                        Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
                    }
                }
                s.error?.let {
                    Spacer(Modifier.width(10.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = cs.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun SendDetailScreen(id: String, onBack: () -> Unit) {
    val vm = sendsVm()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val fmt = LocalFmt.current
    val s = ui.items?.firstOrNull { it.id == id }
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.send_detail)) }, navigationIcon = {
            IconButton(onClick = onBack) { Sym(R.drawable.ic_sym_arrow_back, stringResource(R.string.back)) } }) },
        contentWindowInsets = TopAppBarDefaults.windowInsets,
    ) { pad ->
        if (s == null) { Box(Modifier.padding(pad).fillMaxSize(), Alignment.Center) { LoadingIndicator() }; return@Scaffold }
        val cs = MaterialTheme.colorScheme
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Constrained {
                Column {
                    Surface(Modifier.fillMaxWidth().sharedCard("send-${s.id}"), shape = RoundedCornerShape(28.dp), color = cs.surfaceContainerLow) {
                        Column(Modifier.padding(20.dp)) {
                            Text(stringResource(R.string.msg_to), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                            Text(ltr(fmt.digits(s.number)), style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(R.string.by_agent, s.agent), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                            Spacer(Modifier.height(16.dp))
                            SelectionContainer { Text(s.text, style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content)) }
                        }
                    }
                    SectionTitle(stringResource(R.string.chain_entries), Modifier.padding(start = 0.dp))
                    s.entries.forEach { e ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                            val st = when (e.result) {
                                "delivered" -> SendStatus.DELIVERED; "delivery_failed" -> SendStatus.NOT_DELIVERED; "sent" -> SendStatus.SENT
                                "expired" -> SendStatus.EXPIRED; else -> SendStatus.FAILED
                            }
                            val (icon, bg, fg) = statusLook(st)
                            StatusDot(icon, bg, fg)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(statusLabel(st), style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(R.string.entry_seq, fmt.num(e.seq)) + "  ·  " + fmt.stamp(e.ts), style = MaterialTheme.typography.labelMedium,
                                    color = cs.onSurfaceVariant)
                                e.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.error) }
                                Text(e.hash.take(16) + "…", style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace,
                                    textDirection = TextDirection.Ltr), color = cs.outline)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusDot(icon: Int, bg: Color, fg: Color) {
    Surface(shape = RoundedCornerShape(12.dp), color = bg, modifier = Modifier.size(36.dp)) {
        Box(contentAlignment = Alignment.Center) { Sym(icon, null, Modifier.size(18.dp), fg) }
    }
}
