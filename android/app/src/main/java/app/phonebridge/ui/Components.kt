package app.phonebridge.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toPath
import androidx.graphics.shapes.toPath as toAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import app.phonebridge.Conn
import app.phonebridge.Parse
import app.phonebridge.R

@Composable
fun Sym(@DrawableRes id: Int, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = androidx.compose.material3.LocalContentColor.current) =
    Icon(painterResource(id), contentDescription, modifier, tint)

/**
 * Morphs between two Material expressive polygons whenever [polygon] changes (spring from the motion scheme).
 * The shape is the message: each connection state and each SMS class has its own silhouette.
 */
@Composable
fun MorphShape(polygon: RoundedPolygon, color: Color, modifier: Modifier = Modifier, rotation: () -> Float = { 0f }) {
    var from by remember { mutableStateOf(polygon) }
    var to by remember { mutableStateOf(polygon) }
    val progress = remember { Animatable(1f) }
    val spec = MaterialTheme.motionScheme.slowSpatialSpec<Float>()
    LaunchedEffect(polygon) {
        if (polygon == to) return@LaunchedEffect
        from = to; to = polygon
        progress.snapTo(0f)
        progress.animateTo(1f, spec)
    }
    // Morph() matches the two outlines' features (costly): built only while a morph runs, never for a static shape
    // (every feed avatar). The path is cached until the size or the morph progress changes.
    val morph = remember(from, to) { if (from == to) null else Morph(from, to) }
    // Rotation is read in the draw layer, so a spinning shape redraws without recomposing.
    Spacer(modifier.graphicsLayer { rotationZ = rotation() }.drawWithCache {
        val path = morph?.toPath(progress.value, Path()) ?: to.toAndroidPath().asComposePath()
        path.transform(Matrix().apply { scale(size.width, size.height) })
        onDrawBehind { drawPath(path, color) }
    })
}

fun connPolygon(c: Conn): RoundedPolygon = when (c) {
    Conn.CONNECTED -> MaterialShapes.Cookie9Sided
    Conn.SYNCING -> MaterialShapes.SoftBurst
    Conn.CHECKING -> MaterialShapes.Cookie6Sided
    Conn.OFFLINE -> MaterialShapes.Clover4Leaf
    Conn.PAUSED -> MaterialShapes.Pill
}

fun connIcon(c: Conn) = when (c) {
    Conn.CONNECTED -> R.drawable.ic_sym_cloud_done
    Conn.SYNCING, Conn.CHECKING -> R.drawable.ic_sym_sync
    Conn.OFFLINE -> R.drawable.ic_sym_cloud_off
    Conn.PAUSED -> R.drawable.ic_sym_pause
}

/** The dashboard's one memorable element: a shamseh-like medallion inside the wavy history ring. */
@Composable
fun Medallion(conn: Conn, fraction: Float, label: String, modifier: Modifier = Modifier, size: Dp = 196.dp) {
    val cs = MaterialTheme.colorScheme
    val (fill, onFill) = when (conn) {
        Conn.CONNECTED, Conn.SYNCING -> cs.primaryContainer to cs.onPrimaryContainer
        Conn.CHECKING -> cs.secondaryContainer to cs.onSecondaryContainer
        Conn.OFFLINE -> cs.errorContainer to cs.onErrorContainer
        Conn.PAUSED -> cs.surfaceContainerHighest to cs.onSurfaceVariant
    }
    val turning = conn == Conn.SYNCING || conn == Conn.CHECKING
    // The infinite transition exists only while turning: an idle dashboard renders no frames.
    val angle = if (turning) rememberInfiniteTransition(label = "spin")
        .animateFloat(0f, 360f, infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Restart), label = "a") else null
    Box(modifier.size(size).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        val ring = if (conn == Conn.PAUSED || conn == Conn.OFFLINE) 0f else fraction
        CircularWavyProgressIndicator(
            progress = { ring }, modifier = Modifier.fillMaxSize(),
            color = if (conn == Conn.CONNECTED) cs.tertiary else cs.primary, trackColor = cs.surfaceContainerHighest,
            amplitude = { if (it >= 1f || conn != Conn.SYNCING) 0f else 1f },
        )
        MorphShape(connPolygon(conn), fill, Modifier.size(size * 0.74f), rotation = { angle?.value ?: 0f })
        Sym(connIcon(conn), null, Modifier.size(size * 0.22f), onFill)
    }
}

/** SMS class -> silhouette + colour role; used for list avatars and the filter chips. */
data class ClsStyle(val polygon: RoundedPolygon, @DrawableRes val icon: Int, val label: Int)

fun clsStyle(c: Parse.Cls) = when (c) {
    Parse.Cls.OTP -> ClsStyle(MaterialShapes.Cookie4Sided, R.drawable.ic_sym_password, R.string.cls_otp)
    Parse.Cls.BANK -> ClsStyle(MaterialShapes.Sunny, R.drawable.ic_sym_account_balance, R.string.cls_bank)
    Parse.Cls.PERSONAL -> ClsStyle(MaterialShapes.Circle, R.drawable.ic_sym_person, R.string.cls_personal)
    Parse.Cls.DELIVERY -> ClsStyle(MaterialShapes.Pill, R.drawable.ic_sym_local_shipping, R.string.cls_delivery)
    Parse.Cls.SERVICE -> ClsStyle(MaterialShapes.Square, R.drawable.ic_sym_info, R.string.cls_service)
    Parse.Cls.AD -> ClsStyle(MaterialShapes.Burst, R.drawable.ic_sym_campaign, R.string.cls_ad)
}

@Composable
fun clsColors(c: Parse.Cls): Pair<Color, Color> {
    val cs = MaterialTheme.colorScheme
    return when (c) {
        Parse.Cls.OTP -> cs.tertiaryContainer to cs.onTertiaryContainer
        Parse.Cls.BANK -> cs.secondaryContainer to cs.onSecondaryContainer
        Parse.Cls.PERSONAL -> cs.primaryContainer to cs.onPrimaryContainer
        else -> cs.surfaceContainerHighest to cs.onSurfaceVariant
    }
}

// One unit-square path per polygon, shared by every avatar (main thread only: composition and draw).
private val unitPaths = HashMap<RoundedPolygon, Path>()

/** [polygon] filled with [color]: no animation state, no coroutine, no path building per instance (feed rows). */
@Composable
fun StaticShape(polygon: RoundedPolygon, color: Color, modifier: Modifier = Modifier) {
    val unit = unitPaths.getOrPut(polygon) { polygon.toAndroidPath().asComposePath() }
    Spacer(modifier.drawBehind { scale(size.width, size.height, pivot = Offset.Zero) { drawPath(unit, color) } })
}

/** [animate]: morph when [polygon] changes (rule toggles); list avatars stay static, which is far cheaper to scroll. */
@Composable
fun ShapeAvatar(polygon: RoundedPolygon, @DrawableRes icon: Int, bg: Color, fg: Color, modifier: Modifier = Modifier, size: Dp = 44.dp,
                letter: String? = null, animate: Boolean = false) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (animate) MorphShape(polygon, bg, Modifier.fillMaxSize()) else StaticShape(polygon, bg, Modifier.fillMaxSize())
        if (letter != null) Text(letter, style = MaterialTheme.typography.titleMedium, color = fg)
        else Sym(icon, null, Modifier.size(size * 0.48f), fg)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 10.dp).semantics { heading() })
}

/** Illustrated empty state: three overlapping expressive shapes behind the subject icon. */
@Composable
fun EmptyState(@DrawableRes icon: Int, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(168.dp).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
            MorphShape(MaterialShapes.Clover8Leaf, cs.surfaceContainerHigh, Modifier.size(168.dp), rotation = { 12f })
            Box(Modifier.size(168.dp)) {
                MorphShape(MaterialShapes.Cookie4Sided, cs.tertiaryContainer, Modifier.size(56.dp).align(Alignment.TopEnd))
                MorphShape(MaterialShapes.Sunny, cs.secondaryContainer, Modifier.size(44.dp).align(Alignment.BottomStart))
            }
            MorphShape(MaterialShapes.Cookie9Sided, cs.primaryContainer, Modifier.size(96.dp))
            Sym(icon, null, Modifier.size(40.dp), cs.onPrimaryContainer)
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(20.dp)); action() }
    }
}

/** Placeholder rows with a soft travelling highlight while the first page loads. */
@Composable
fun SkeletonList(rows: Int = 7, contentPadding: PaddingValues = PaddingValues(0.dp)) {
    val cs = MaterialTheme.colorScheme
    val t = rememberInfiniteTransition(label = "sk")
    val a by t.animateFloat(0.45f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    val c = cs.surfaceContainerHighest.copy(alpha = a)
    Column(Modifier.padding(contentPadding).clearAndSetSemantics { }) {
        repeat(rows) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(50)).background(c))
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.fillMaxWidth(0.4f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(c))
                    Box(Modifier.fillMaxWidth(0.85f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(c))
                }
            }
        }
    }
}
