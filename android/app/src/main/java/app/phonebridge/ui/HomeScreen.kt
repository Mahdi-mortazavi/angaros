package app.phonebridge.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.TextAutoSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.phonebridge.ChainState
import app.phonebridge.Conn
import app.phonebridge.Fmt
import app.phonebridge.HomeViewModel
import app.phonebridge.Parse
import app.phonebridge.R
import app.phonebridge.isolate
import app.phonebridge.ltr
import app.phonebridge.Repo
import kotlinx.coroutines.launch

/** Relative time ("2 minutes ago") with digits that follow the owner's choice. */
@Composable
fun ago(ms: Long): String {
    val fmt = LocalFmt.current
    if (ms <= 0) return stringResource(R.string.never)
    return fmt.digits(Parse.digits(DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS).toString()))
}

@Composable
fun HomeScreen(onOpenFeed: () -> Unit, onOpenSends: () -> Unit) {
    val ctx = LocalContext.current
    val fmtNow by rememberUpdatedState(LocalFmt.current)
    val vm: HomeViewModel = viewModel(factory = viewModelFactory { initializer { HomeViewModel(Repo.get(ctx)) { fmtNow } } })
    val ui by vm.ui.collectAsStateWithLifecycle()
    val fmt = LocalFmt.current
    val snack = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val pausedMsg = stringResource(R.string.paused_snack)
    val resumedMsg = stringResource(R.string.resumed_snack)
    val undo = stringResource(R.string.undo)
    val syncMsg = stringResource(R.string.sync_started)

    fun togglePause() {
        val on = !ui.settings.paused
        haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
        vm.setPaused(on)
        scope.launch {
            snack.currentSnackbarData?.dismiss()
            if (snack.showSnackbar(if (on) pausedMsg else resumedMsg, undo, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed)
                vm.setPaused(!on)
        }
    }

    // Horizontal insets (landscape cutouts, gesture edges next to the navigation rail); top is handled by the header.
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(androidx.compose.foundation.layout.WindowInsetsSides.Horizontal))) {
        LazyColumn(
            Modifier.fillMaxSize(),
            // The bottom bar (portrait) or the gesture area (landscape, rail) must not cover the last section.
            contentPadding = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom).add(WindowInsets(bottom = 24.dp)).asPaddingValues(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { Header() }
            item { Hero(ui.conn, ui.fraction, ui.health.lastPoll.coerceAtLeast(ui.health.lastUpload)) }
            // In the flow under the status, not floating over the list: a floating bar covered the status line in
            // landscape and a section title in portrait (QA 3.0.2, 30/31-final-*.png) at every scroll position.
            item {
                HorizontalFloatingToolbar(
                    expanded = true,
                    modifier = Modifier.padding(top = 16.dp),
                    colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
                ) {
                    ToolbarAction(R.drawable.ic_sym_sync, stringResource(R.string.action_sync)) {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm); vm.syncNow()
                        scope.launch { snack.currentSnackbarData?.dismiss(); snack.showSnackbar(syncMsg, duration = SnackbarDuration.Short) }
                    }
                    ToolbarAction(if (ui.settings.paused) R.drawable.ic_sym_play_arrow else R.drawable.ic_sym_pause,
                        stringResource(if (ui.settings.paused) R.string.action_resume else R.string.action_pause)) { togglePause() }
                    ToolbarAction(R.drawable.ic_sym_network_check, stringResource(R.string.action_check)) {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick); vm.recheckRelay()
                    }
                }
            }
            item { Constrained { TodayRow(ui.counters, fmt, onOpenFeed) } }
            item { Constrained { Sims(ui.health.sims, ui.settings.defaultSim, fmt) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); vm.setDefaultSim(it) } } }
            item { Constrained { HealthList(ui, fmt, onOpenSends, ctx) } }
            item { Constrained { History(ui.health.progress, ui.settings, fmt) } }
        }
        // Edge-to-edge without a top bar: scrolled content must not run under the status bar icons.
        Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)))
    }
}

@Composable
private fun ToolbarAction(icon: Int, label: String, onClick: () -> Unit) {
    androidx.compose.material3.TooltipBox(
        positionProvider = androidx.compose.material3.TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = androidx.compose.material3.rememberTooltipState(),
    ) {
        IconButton(onClick = onClick) { Sym(icon, label) }
    }
}

/**
 * Keeps content readable on wide screens (tablets, landscape): one centred column of at most 640dp.
 * A Column, not a Box: callers emit a section title and its body, which a Box drew on top of each other.
 */
@Composable
fun Constrained(content: @Composable () -> Unit) = Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) { content() }
}

@Composable
private fun Header() {
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(androidx.compose.foundation.layout.WindowInsetsSides.Top))
            .padding(start = 20.dp, end = 20.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(R.drawable.ic_brand_mark), null, Modifier.size(36.dp))
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun Hero(conn: Conn, fraction: Float, lastContact: Long) {
    val fmt = LocalFmt.current
    val title = stringResource(when (conn) {
        Conn.CONNECTED -> R.string.state_connected; Conn.SYNCING -> R.string.state_syncing; Conn.OFFLINE -> R.string.state_offline
        Conn.PAUSED -> R.string.state_paused; Conn.CHECKING -> R.string.state_checking
    })
    val sub = when (conn) {
        Conn.SYNCING -> stringResource(R.string.state_sub_syncing, fmt.digits((fraction * 100).toInt().toString()))
        Conn.OFFLINE -> stringResource(R.string.state_sub_offline)
        Conn.PAUSED -> stringResource(R.string.state_sub_paused)
        else -> stringResource(R.string.state_sub_connected)
    }
    Column(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 8.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Medallion(conn, fraction, "$title. $sub")
        Spacer(Modifier.height(24.dp))
        androidx.compose.animation.AnimatedContent(title, label = "title") { t ->
            Text(t, style = MaterialTheme.typography.headlineMediumEmphasized, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(6.dp))
        Text(sub, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (lastContact > 0) {
            Spacer(Modifier.height(2.dp))
            Text(stringResource(R.string.last_contact, ago(lastContact)), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun TodayRow(c: app.phonebridge.Counters?, fmt: Fmt, onOpenFeed: () -> Unit) {
    SectionTitle(stringResource(R.string.today))
    val cs = MaterialTheme.colorScheme
    Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = cs.surfaceContainerLow) {
        Row(Modifier.height(IntrinsicSize.Min).padding(vertical = 18.dp)) {
            val cells = listOf(
                Triple(R.string.count_received, c?.received, null),
                Triple(R.string.count_sent, c?.sent, null),
                Triple(R.string.count_otp, c?.otp, cs.tertiary),
                Triple(R.string.count_bank, c?.bank, cs.secondary),
            )
            cells.forEachIndexed { i, (label, n, accent) ->
                if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 6.dp).background(cs.outlineVariant))
                val text = stringResource(label)
                Column(
                    Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp))
                        .clickable(role = Role.Button, onClick = onOpenFeed)
                        .clearAndSetSemantics { contentDescription = "$text ${n ?: ""}" },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(n?.let { fmt.num(it) } ?: "–", style = MaterialTheme.typography.headlineSmallEmphasized, color = accent ?: cs.onSurface)
                    Text(text, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun Sims(sims: List<Pair<Int, String>>, defaultSim: Int, fmt: Fmt, onPick: (Int) -> Unit) {
    SectionTitle(stringResource(R.string.sims))
    val cs = MaterialTheme.colorScheme
    if (sims.isEmpty()) {
        Text(stringResource(R.string.sims_unavailable), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp))
        return
    }
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        sims.forEach { (slot, name) ->
            val isDefault = slot == defaultSim
            Surface(Modifier.weight(1f), shape = RoundedCornerShape(24.dp),
                color = if (isDefault) cs.secondaryContainer else cs.surfaceContainerLow) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    ShapeAvatar(androidx.compose.material3.MaterialShapes.Square, R.drawable.ic_sym_sim_card,
                        if (isDefault) cs.secondary else cs.surfaceContainerHighest, if (isDefault) cs.onSecondary else cs.onSurfaceVariant,
                        size = 40.dp, letter = fmt.digits(slot.toString()))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(if (isDefault) R.string.sim_default_badge else R.string.sim_slot, fmt.digits(slot.toString())),
                            style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.sim_default_label), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp))
    Spacer(Modifier.height(8.dp))
    val options = listOf(0 to stringResource(R.string.sim_system)) + sims.map { it.first to stringResource(R.string.sim_slot, fmt.digits(it.first.toString())) }
    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { i, (slot, label) ->
            ToggleButton(
                checked = defaultSim == slot, onCheckedChange = { if (it) onPick(slot) },
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
            ) { Text(label, maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = MaterialTheme.typography.labelLarge.fontSize)) }
        }
    }
}

private data class HealthRow(val icon: Int, val label: Int, val value: String, val ok: Boolean?, val onClick: (() -> Unit)? = null)

@Composable
private fun HealthList(ui: app.phonebridge.HomeUi, fmt: Fmt, onOpenSends: () -> Unit, ctx: android.content.Context) {
    SectionTitle(stringResource(R.string.health))
    val h = ui.health
    val rows = buildList {
        add(HealthRow(R.drawable.ic_sym_dns, R.string.health_relay,
            when { ui.relayMs == null -> stringResource(R.string.relay_checking); ui.relayMs < 0 -> stringResource(R.string.relay_down)
                else -> stringResource(R.string.relay_ok, fmt.num(ui.relayMs)) }, ui.relayMs?.let { it >= 0 }))
        add(HealthRow(R.drawable.ic_sym_history, R.string.health_last_poll, ago(h.lastPoll),
            if (ui.settings.paused) null else System.currentTimeMillis() - h.lastPoll < 120_000))
        add(HealthRow(R.drawable.ic_sym_schedule, R.string.health_last_upload, ago(h.lastUpload), null))
        add(HealthRow(R.drawable.ic_sym_inbox, R.string.health_queue,
            if (h.queue == 0L) stringResource(R.string.queue_empty) else stringResource(R.string.queue_items, fmt.num(h.queue)), h.queue < 50))
        add(HealthRow(if (h.battery) R.drawable.ic_sym_battery_full else R.drawable.ic_sym_battery_alert, R.string.health_battery,
            stringResource(if (h.battery) R.string.battery_ok else R.string.battery_bad), h.battery,
            if (h.battery) null else ({ requestBattery(ctx) })))
        add(HealthRow(R.drawable.ic_sym_shield, R.string.health_perms,
            if (h.missingPerms.isEmpty()) stringResource(R.string.onb_perm_all) else stringResource(R.string.perms_missing, fmt.num(h.missingPerms.size)),
            h.missingPerms.isEmpty(), if (h.missingPerms.isEmpty()) null else ({ openAppSettings(ctx) })))
        val chain = ui.chain
        add(HealthRow(if (chain is ChainState.Broken) R.drawable.ic_sym_gpp_bad else R.drawable.ic_sym_verified, R.string.health_chain,
            when (chain) {
                ChainState.Checking -> stringResource(R.string.chain_checking)
                ChainState.Empty -> stringResource(R.string.chain_empty)
                is ChainState.Ok -> stringResource(R.string.chain_ok, fmt.num(chain.entries))
                is ChainState.Broken -> stringResource(R.string.chain_broken, fmt.num(chain.atSeq))
            }, chain !is ChainState.Broken, onOpenSends))
        h.lastError?.let { add(HealthRow(R.drawable.ic_sym_error, R.string.health_error, ltr(it), null)) }
    }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        rows.forEachIndexed { i, r ->
            SegmentedListItem(
                onClick = r.onClick ?: {}, enabled = true,
                shapes = ListItemDefaults.segmentedShapes(i, rows.size),
                leadingContent = { StatusIcon(r.icon, r.ok) },
                supportingContent = { Text(r.value, maxLines = 3, overflow = TextOverflow.Ellipsis) },
            ) { Text(stringResource(r.label)) }
        }
    }
}

@Composable
fun StatusIcon(icon: Int, ok: Boolean?) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (ok) {
        true -> cs.secondaryContainer to cs.onSecondaryContainer
        false -> cs.errorContainer to cs.onErrorContainer
        null -> cs.surfaceContainerHighest to cs.onSurfaceVariant
    }
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(bg), contentAlignment = Alignment.Center) {
        Sym(icon, null, Modifier.size(22.dp), fg)
    }
}

@Composable
private fun History(progress: Map<String, Pair<Long, Long>>, s: app.phonebridge.Settings, fmt: Fmt) {
    SectionTitle(stringResource(R.string.history))
    val cs = MaterialTheme.colorScheme
    val kinds = listOf(Triple("sms", R.string.backfill_sms, s.syncSms), Triple("calls", R.string.backfill_calls, s.syncCalls),
        Triple("contacts", R.string.backfill_contacts, s.syncContacts))
    Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = cs.surfaceContainerLow) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            kinds.forEach { (k, label, on) ->
                val (done, total) = progress[k] ?: (0L to 0L)
                val f = if (total > 0) done.toFloat() / total else 1f
                Column(Modifier.semantics(mergeDescendants = true) { }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(label), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text(if (on) stringResource(R.string.backfill_of, fmt.num(done), fmt.num(total)) else stringResource(R.string.backfill_off),
                            style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearWavyProgressIndicator(progress = { if (on) f else 0f }, modifier = Modifier.fillMaxWidth(),
                        color = if (f >= 1f) cs.tertiary else cs.primary, trackColor = cs.surfaceContainerHighest,
                        amplitude = { if (it >= 1f || it <= 0f) 0f else 1f })
                }
            }
        }
    }
}

@android.annotation.SuppressLint("BatteryLife") // the bridge is useless if Doze kills its long-poll; the user decides
fun requestBattery(ctx: android.content.Context) = runCatching {
    ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun openAppSettings(ctx: android.content.Context) = runCatching {
    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
