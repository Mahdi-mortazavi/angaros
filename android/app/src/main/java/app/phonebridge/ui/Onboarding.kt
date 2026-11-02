package app.phonebridge.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.phonebridge.OnboardingViewModel
import app.phonebridge.Lang
import app.phonebridge.R
import app.phonebridge.Repo
import app.phonebridge.Sync
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

private val STEP_SHAPES = listOf(MaterialShapes.Cookie9Sided, MaterialShapes.SoftBurst, MaterialShapes.Sunny)
private val STEP_ICONS = listOf(R.drawable.ic_sym_shield, R.drawable.ic_sym_battery_full, R.drawable.ic_sym_qr_code_scanner)

@Composable
fun Onboarding(pairingEnabled: Boolean = true) {
    val ctx = LocalContext.current
    val repo = Repo.get(ctx)
    val vm: OnboardingViewModel = viewModel(factory = viewModelFactory { initializer { OnboardingViewModel(repo) } })
    val step by vm.step.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    var tick by rememberSaveable { mutableIntStateOf(0) } // bumps on resume: re-read permissions / battery
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    val missing = remember(tick) { repo.missingPermissions() }
    val battery = remember(tick) { Sync.batteryUnrestricted(ctx) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }

    BackHandler(enabled = step > 0) { vm.step.value = step - 1 }

    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().weight(1f).padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.ic_brand_mark), null, Modifier.size(32.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.onb_step, LocalFmt.current.digits("${step + 1}"), LocalFmt.current.digits("3")),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            val p by androidx.compose.animation.core.animateFloatAsState((step + 1) / 3f, MaterialTheme.motionScheme.slowSpatialSpec(), label = "p")
            LinearWavyProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
            val mIn = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
            val mOut = MaterialTheme.motionScheme.fastSpatialSpec<androidx.compose.ui.unit.IntOffset>()
            val fIn = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
            val fOut = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
            AnimatedContent(step, label = "step", modifier = Modifier.weight(1f), transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally(mIn) { it / 4 * dir } + fadeIn(fIn)) togetherWith (slideOutHorizontally(mOut) { -it / 4 * dir } + fadeOut(fOut))
            }) { s ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(if (s == 0) 16.dp else 28.dp))
                    if (s == 0) { LanguagePicker(); Spacer(Modifier.height(16.dp)) }
                    StepArt(s)
                    Spacer(Modifier.height(28.dp))
                    when (s) {
                        0 -> PermStep(missing) { ask.launch(repo.neededPermissions.toTypedArray()) }
                        1 -> BatteryStep(battery) { requestBattery(ctx) }
                        else -> PairStep(vm, pairingEnabled) { haptics.performHapticFeedback(HapticFeedbackType.Confirm) }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
        // Bottom bar: back / next. The last step's primary action lives in the step (Pair).
        Row(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (step > 0) TextButton(onClick = { vm.step.value = step - 1 }) { Text(stringResource(R.string.back)) }
            Spacer(Modifier.weight(1f))
            if (step < 2) {
                val pending = (step == 0 && missing.isNotEmpty()) || (step == 1 && !battery)
                val next = { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); vm.step.value = step + 1 }
                if (pending) TextButton(onClick = next) { Text(stringResource(R.string.onb_skip)) }
                else Button(onClick = next, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.onb_next)) }
            }
        }
    }
}

/** English | فارسی on the first screen; the choice applies at once (see [Lang.apply]). */
@Composable
private fun LanguagePicker() {
    val ctx = LocalContext.current
    val repo = Repo.get(ctx)
    val scope = rememberCoroutineScope()
    val current = if (isFa()) Lang.FA else Lang.EN
    val options = listOf(Lang.EN to R.string.lang_en, Lang.FA to R.string.lang_fa)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.onb_language), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
            options.forEachIndexed { i, (tag, label) ->
                ToggleButton(checked = current == tag, onCheckedChange = {
                    if (it && tag != current) scope.launch {
                        repo.setLanguage(tag)
                        if (Build.VERSION.SDK_INT < 33) (ctx as? android.app.Activity)?.recreate()
                    }
                }, modifier = Modifier.testTag("lang_$tag"),
                    shapes = if (i == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes()) {
                    Text(stringResource(label), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun StepArt(step: Int) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.size(180.dp), contentAlignment = Alignment.Center) {
        MorphShape(MaterialShapes.Clover8Leaf, cs.surfaceContainerHigh, Modifier.size(180.dp), rotation = { step * 20f })
        MorphShape(STEP_SHAPES[step], cs.primaryContainer, Modifier.size(124.dp))
        Sym(STEP_ICONS[step], null, Modifier.size(52.dp), cs.onPrimaryContainer)
    }
}

@Composable
private fun StepText(title: Int, body: AnnotatedString) {
    Text(stringResource(title), style = MaterialTheme.typography.headlineMediumEmphasized, textAlign = TextAlign.Center,
        modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(12.dp))
    Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}

@Composable
private fun PermStep(missing: List<String>, onGrant: () -> Unit) {
    StepText(R.string.onb_perm_title, AnnotatedString(stringResource(R.string.onb_perm_body, stringResource(R.string.app_name))))
    Spacer(Modifier.height(24.dp))
    val groups = buildList {
        add(Triple(R.string.perm_sms, R.string.perm_sms_desc, listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS)) to R.drawable.ic_sym_sms)
        add(Triple(R.string.perm_contacts, R.string.perm_contacts_desc, listOf(Manifest.permission.READ_CONTACTS)) to R.drawable.ic_sym_contacts)
        add(Triple(R.string.perm_calls, R.string.perm_calls_desc, listOf(Manifest.permission.READ_CALL_LOG)) to R.drawable.ic_sym_call)
        add(Triple(R.string.perm_phone, R.string.perm_phone_desc, listOf(Manifest.permission.READ_PHONE_STATE)) to R.drawable.ic_sym_sim_card)
        if (Build.VERSION.SDK_INT >= 33) add(Triple(R.string.perm_notif, R.string.perm_notif_desc, listOf(Manifest.permission.POST_NOTIFICATIONS)) to R.drawable.ic_sym_notifications)
    }
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(28.dp), color = cs.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            groups.forEach { (g, icon) ->
                val ok = g.third.none { it in missing }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).semantics(mergeDescendants = true) { },
                    verticalAlignment = Alignment.CenterVertically) {
                    StatusIcon(icon, if (ok) true else null)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(g.first), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(g.second), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    Text(stringResource(if (ok) R.string.granted else R.string.not_granted), style = MaterialTheme.typography.labelMedium,
                        color = if (ok) cs.secondary else cs.onSurfaceVariant)
                }
            }
        }
    }
    Spacer(Modifier.height(20.dp))
    if (missing.isEmpty()) Text(stringResource(R.string.onb_perm_all), style = MaterialTheme.typography.titleSmall, color = cs.secondary)
    else Button(onClick = onGrant, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.onb_perm_grant)) }
}

@Composable
private fun BatteryStep(ok: Boolean, onGrant: () -> Unit) {
    StepText(R.string.onb_bat_title, AnnotatedString(stringResource(R.string.onb_bat_body)))
    Spacer(Modifier.height(28.dp))
    if (ok) Row(verticalAlignment = Alignment.CenterVertically) {
        StatusIcon(R.drawable.ic_sym_check_circle, true); Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.onb_bat_ok), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary)
    } else Button(onClick = onGrant, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.onb_bat_grant)) }
}

@Composable
private fun PairStep(vm: OnboardingViewModel, enabled: Boolean, onPaired: () -> Unit) {
    val ctx = LocalContext.current
    var code by rememberSaveable { mutableStateOf("") }
    val err by vm.error.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val prompt = stringResource(R.string.scan_prompt)
    val scan = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { code = it; if (enabled) vm.pair(it, onPaired) } }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(prompt).setBeepEnabled(false).setOrientationLocked(false))
    }
    StepText(R.string.onb_pair_title, AnnotatedString.fromHtml(stringResource(R.string.onb_pair_body)))
    Spacer(Modifier.height(24.dp))
    Button(onClick = {
        if (ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(prompt).setBeepEnabled(false).setOrientationLocked(false))
        else camera.launch(Manifest.permission.CAMERA)
    }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shapes = ButtonDefaults.shapes()) {
        Sym(R.drawable.ic_sym_qr_code_scanner, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp)); Text(stringResource(R.string.onb_scan))
    }
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        code, { code = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(stringResource(R.string.onb_paste_label)) }, isError = err != null,
        textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
        trailingIcon = {
            IconButton(onClick = { ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(ctx)?.let { code = it.toString() } }) {
                Sym(R.drawable.ic_sym_content_paste, stringResource(R.string.onb_paste))
            }
        },
        supportingText = err?.let { { Text(stringResource(R.string.pair_fail, it), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) } },
    )
    Spacer(Modifier.height(12.dp))
    if (busy) LoadingIndicator()
    else FilledTonalButton(onClick = { vm.pair(code, onPaired) }, enabled = enabled && code.isNotBlank(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.onb_pair)) }
}
