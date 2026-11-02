package app.phonebridge.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.phonebridge.Fmt
import app.phonebridge.R
import app.phonebridge.Repo
import app.phonebridge.Rule
import app.phonebridge.SettingsViewModel
import app.phonebridge.parseRules

/** Read-only mirror of the PC automation rules (PROTOCOL.md v3 `rules_mirror`). */
@Composable
fun RulesScreen() {
    val ctx = LocalContext.current
    val vm: SettingsViewModel = viewModel(factory = viewModelFactory { initializer { SettingsViewModel(Repo.get(ctx)) } })
    val s by vm.settings.collectAsStateWithLifecycle()
    val (received, rules) = remember(s.rules) { parseRules(s.rules?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }) }
    val fmt = LocalFmt.current
    Scaffold(topBar = {
        TopAppBar(title = {
            Column {
                Text(stringResource(R.string.rules_title))
                if (received != null) Text(stringResource(R.string.rules_sub, ago(received)), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        })
    }, contentWindowInsets = TopAppBarDefaults.windowInsets) { pad ->
        if (rules.isEmpty()) {
            Column(Modifier.padding(pad).fillMaxSize()) {
                EmptyState(R.drawable.ic_sym_bolt, stringResource(R.string.rules_empty_title), stringResource(R.string.rules_empty_body))
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Sym(R.drawable.ic_sym_lock_open, null, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.rules_readonly), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(rules) { r -> Constrained { RuleCard(r, fmt) } }
        }
    }
}

@Composable
private fun RuleCard(r: Rule, fmt: Fmt) {
    val cs = MaterialTheme.colorScheme
    val failed = r.lastOk == false
    Surface(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }, shape = RoundedCornerShape(24.dp),
        color = if (r.enabled) cs.surfaceContainerLow else cs.surfaceContainerLowest) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShapeAvatar(if (r.enabled) MaterialShapes.Sunny else MaterialShapes.Circle, R.drawable.ic_sym_bolt,
                    if (r.enabled) cs.tertiaryContainer else cs.surfaceContainerHighest, if (r.enabled) cs.onTertiaryContainer else cs.onSurfaceVariant, size = 40.dp, animate = true)
                Spacer(Modifier.width(12.dp))
                Text(r.name, style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content), modifier = Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(50), color = if (r.enabled) cs.secondaryContainer else cs.surfaceContainerHighest) {
                    Text(stringResource(if (r.enabled) R.string.rule_on else R.string.rule_off), style = MaterialTheme.typography.labelLarge,
                        color = if (r.enabled) cs.onSecondaryContainer else cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Labeled(stringResource(R.string.rule_when), r.trigger)
            Spacer(Modifier.height(6.dp))
            Labeled(stringResource(R.string.rule_then), r.action)
            Spacer(Modifier.height(10.dp))
            Text(
                (r.lastRun?.let { stringResource(R.string.rule_last, ago(it)) } ?: stringResource(R.string.rule_never)) +
                    "  ·  " + stringResource(R.string.rule_runs, fmt.num(r.runs)) +
                    (if (failed) "  ·  " + stringResource(R.string.rule_failed) else ""),
                style = MaterialTheme.typography.labelMedium, color = if (failed) cs.error else cs.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Labeled(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(64.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content), modifier = Modifier.weight(1f))
    }
}
