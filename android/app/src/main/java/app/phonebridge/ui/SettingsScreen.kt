package app.phonebridge.ui

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.phonebridge.Lang
import app.phonebridge.R
import app.phonebridge.isolate
import app.phonebridge.ltr
import app.phonebridge.Repo
import app.phonebridge.SettingsViewModel
import app.phonebridge.Sync
import kotlinx.coroutines.launch

private class Row_(val icon: Int, val title: String, val desc: String?, val checked: Boolean? = null, val onToggle: ((Boolean) -> Unit)? = null,
                   val onClick: (() -> Unit)? = null, val danger: Boolean = false)

@Composable
private fun Group(title: String, rows: List<Row_>) {
    SectionTitle(title)
    val cs = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        rows.forEachIndexed { i, r ->
            val toggle = r.onToggle
            SegmentedListItem(
                onClick = { if (toggle != null && r.checked != null) toggle(!r.checked) else r.onClick?.invoke() },
                shapes = ListItemDefaults.segmentedShapes(i, rows.size),
                leadingContent = { Sym(r.icon, null, tint = if (r.danger) cs.error else cs.onSurfaceVariant) },
                trailingContent = if (r.checked != null && toggle != null) ({ Switch(checked = r.checked, onCheckedChange = null) }) else null,
                supportingContent = r.desc?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
                modifier = if (r.checked != null && toggle != null) Modifier.semantics { role = Role.Switch; toggleableState = ToggleableState(r.checked) } else Modifier,
            ) { Text(r.title, color = if (r.danger) cs.error else cs.onSurface) }
        }
    }
}

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val vm: SettingsViewModel = viewModel(factory = viewModelFactory { initializer { SettingsViewModel(Repo.get(ctx)) } })
    val s by vm.settings.collectAsStateWithLifecycle()
    val fmt = LocalFmt.current
    val haptics = LocalHapticFeedback.current
    val snack = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var relayDialog by rememberSaveable { mutableStateOf(false) }
    var langDialog by rememberSaveable { mutableStateOf(false) }
    var unpairDialog by rememberSaveable { mutableStateOf(false) }
    val res = LocalResources.current
    val str = { id: Int -> res.getString(id) }
    val fa = isFa()

    fun toggled(on: Boolean) = haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_settings)) }) }, contentWindowInsets = TopAppBarDefaults.windowInsets) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item { Constrained { Group(stringResource(R.string.set_connection), listOf(
                Row_(R.drawable.ic_sym_dns, stringResource(R.string.relay_url),
                    s.relayOverride?.let { stringResource(R.string.relay_url_custom, ltr(it)) }
                        ?: stringResource(R.string.relay_url_pairing, ltr(s.pairedRelay ?: "")), onClick = { relayDialog = true }),
                Row_(R.drawable.ic_sym_pause, stringResource(R.string.pause_bridge), stringResource(R.string.pause_bridge_desc), s.paused, { on ->
                    toggled(on); vm.setPaused(on)
                    scope.launch {
                        snack.currentSnackbarData?.dismiss()
                        if (snack.showSnackbar(str(if (on) R.string.paused_snack else R.string.resumed_snack), str(R.string.undo),
                                duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) vm.setPaused(!on)
                    }
                }),
                Row_(R.drawable.ic_sym_sync, stringResource(R.string.resync), stringResource(R.string.resync_desc), onClick = {
                    vm.resync(); scope.launch { snack.showSnackbar(str(R.string.resync_started), duration = SnackbarDuration.Short) }
                }),
            )) } }
            item { Constrained { Group(stringResource(R.string.set_privacy), listOf(
                Row_(R.drawable.ic_sym_sms, stringResource(R.string.privacy_sms), stringResource(if (s.syncSms) R.string.privacy_on_desc else R.string.privacy_off_desc),
                    s.syncSms, { toggled(it); vm.setPrivacy("sms", it) }),
                Row_(R.drawable.ic_sym_contacts, stringResource(R.string.privacy_contacts), stringResource(if (s.syncContacts) R.string.privacy_on_desc else R.string.privacy_off_desc),
                    s.syncContacts, { toggled(it); vm.setPrivacy("contacts", it) }),
                Row_(R.drawable.ic_sym_call, stringResource(R.string.privacy_calls), stringResource(if (s.syncCalls) R.string.privacy_on_desc else R.string.privacy_off_desc),
                    s.syncCalls, { toggled(it); vm.setPrivacy("calls", it) }),
                Row_(R.drawable.ic_sym_call_missed, stringResource(R.string.allow_calls), stringResource(R.string.allow_calls_desc),
                    s.allowCalls, { toggled(it); vm.setAllowCalls(it) }),
            )) } }
            item {
                Constrained {
                    Column {
                        Group(stringResource(R.string.set_appearance), buildList {
                            add(Row_(R.drawable.ic_sym_translate, stringResource(R.string.language),
                                stringResource(if (s.language == Lang.FA) R.string.lang_fa else R.string.lang_en), onClick = { langDialog = true }))
                            if (Build.VERSION.SDK_INT >= 31) add(Row_(R.drawable.ic_sym_palette, stringResource(R.string.dynamic_color),
                                stringResource(R.string.dynamic_color_desc, stringResource(R.string.app_name)), s.dynamicColor, { toggled(it); vm.setDynamicColor(it) }))
                            add(Row_(R.drawable.ic_sym_language, stringResource(R.string.persian_digits), fmt.digits("0123456789"),
                                s.persianDigits ?: fa, { toggled(it); vm.setPersianDigits(it) }))
                            add(Row_(R.drawable.ic_sym_schedule, stringResource(R.string.jalali), fmt.date(System.currentTimeMillis()),
                                s.jalali ?: fa, { toggled(it); vm.setJalali(it) }))
                        })
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.theme), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp))
                        Spacer(Modifier.height(8.dp))
                        val themes = listOf("system" to R.string.theme_system, "light" to R.string.theme_light, "dark" to R.string.theme_dark)
                        Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                            themes.forEachIndexed { i, (v, label) ->
                                ToggleButton(checked = s.theme == v, onCheckedChange = { if (it) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); vm.setTheme(v) } },
                                    modifier = Modifier.weight(1f),
                                    shapes = when (i) { 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes(); themes.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes() }) { Text(stringResource(label), maxLines = 1) }
                            }
                        }
                    }
                }
            }
            item { Constrained { Group(stringResource(R.string.set_diagnostics), listOf(
                Row_(R.drawable.ic_sym_ios_share, stringResource(R.string.export_diag), stringResource(R.string.export_diag_desc), onClick = {
                    scope.launch {
                        val text = vm.diagnostics()
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, res.getString(R.string.diag_subject, res.getString(R.string.app_name))).putExtra(Intent.EXTRA_TEXT, text), null))
                    }
                }),
                Row_(R.drawable.ic_sym_info, stringResource(R.string.version), fmt.digits(Sync.version(ctx)) + "\n" + stringResource(R.string.licenses)),
            )) } }
            item { Constrained { Group(stringResource(R.string.set_about), listOf(
                Row_(R.drawable.ic_sym_verified, stringResource(R.string.app_name), stringResource(R.string.tagline) + "\n" + stringResource(R.string.about_story)),
            )) } }
            item { Constrained { Group(stringResource(R.string.set_danger), listOf(
                Row_(R.drawable.ic_sym_link_off, stringResource(R.string.unpair), stringResource(R.string.unpair_desc), onClick = { unpairDialog = true }, danger = true),
            )) } }
        }
    }

    if (relayDialog) RelayDialog(s.relayOverride ?: "", onDismiss = { relayDialog = false }) { vm.setRelay(it); relayDialog = false }
    if (langDialog) LanguageDialog(s.language, onDismiss = { langDialog = false }) { tag ->
        langDialog = false
        scope.launch {
            vm.setLanguage(tag).join()
            // API 33+ restarts the activity through LocaleManager; older releases need an explicit recreate.
            if (Build.VERSION.SDK_INT < 33) (ctx as? android.app.Activity)?.recreate()
        }
    }
    if (unpairDialog) UnpairDialog(onDismiss = { unpairDialog = false }) { wipeAudit ->
        unpairDialog = false
        haptics.performHapticFeedback(HapticFeedbackType.Reject)
        // The bridge pauses at once and the wipe runs in the app scope after the undo window, so leaving this
        // screen cannot cancel it; "Undo" cancels the wipe and resumes.
        vm.scheduleUnpair(wipeAudit)
        scope.launch {
            snack.currentSnackbarData?.dismiss()
            if (snack.showSnackbar(str(R.string.unpaired_snack), str(R.string.undo), duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed)
                vm.cancelUnpair()
        }
    }
}

@Composable
private fun RelayDialog(initial: String, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var v by rememberSaveable { mutableStateOf(initial) }
    val bad = v.isNotBlank() && !v.trim().startsWith("https://")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.relay_url)) },
        text = {
            OutlinedTextField(v, { v = it }, singleLine = true, isError = bad,
                placeholder = { Text(stringResource(R.string.relay_url_hint)) },
                supportingText = { Text(stringResource(if (bad) R.string.relay_url_bad else R.string.relay_url_help)) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        },
        confirmButton = { TextButton(onClick = { onSave(v.trim().ifBlank { null }) }, enabled = !bad) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun LanguageDialog(current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val options = listOf(Lang.EN to stringResource(R.string.lang_en), Lang.FA to stringResource(R.string.lang_fa))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.language)) },
        text = {
            Column {
                options.forEach { (tag, label) ->
                    Row(Modifier.fillMaxWidth().selectable(current == tag, role = Role.RadioButton) { onPick(tag) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = current == tag, onClick = null)
                        Spacer(Modifier.width(16.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun UnpairDialog(onDismiss: () -> Unit, onConfirm: (Boolean) -> Unit) {
    var wipe by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Sym(R.drawable.ic_sym_link_off, null) },
        title = { Text(stringResource(R.string.unpair_title)) },
        text = {
            Column {
                Text(stringResource(R.string.unpair_body))
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().toggleable(wipe, role = Role.Checkbox) { wipe = it }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = wipe, onCheckedChange = null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.unpair_wipe_audit))
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(wipe) }) { Text(stringResource(R.string.unpair_confirm), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
