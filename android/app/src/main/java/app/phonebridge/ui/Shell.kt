package app.phonebridge.ui

import android.app.Activity
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.unit.dp
import app.phonebridge.R
import app.phonebridge.Settings

val LocalSnackbar = staticCompositionLocalOf { SnackbarHostState() }
val LocalShared = staticCompositionLocalOf<SharedTransitionScope?> { null }
val LocalAnimScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

private data class Dest(val route: String, val label: Int, val icon: Int, val selectedIcon: Int)

private val DESTS = listOf(
    Dest("home", R.string.nav_home, R.drawable.ic_sym_home, R.drawable.ic_sym_home_fill),
    Dest("feed", R.string.nav_activity, R.drawable.ic_sym_forum, R.drawable.ic_sym_forum_fill),
    Dest("sends", R.string.nav_sends, R.drawable.ic_sym_outgoing_mail, R.drawable.ic_sym_outgoing_mail_fill),
    Dest("rules", R.string.nav_rules, R.drawable.ic_sym_bolt, R.drawable.ic_sym_bolt_fill),
    Dest("settings", R.string.nav_settings, R.drawable.ic_sym_settings, R.drawable.ic_sym_settings_fill),
)

@Composable
fun AppRoot(s: Settings, pairingEnabled: Boolean = true, startRoute: String = "home") {
    val dark = LocalDark.current
    val view = LocalView.current
    SideEffect {
        // The theme can differ from the system setting (Settings > Theme): keep the bar icons readable.
        val w = (view.context as Activity).window
        WindowCompat.getInsetsController(w, view).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
    }
    val snack = remember { SnackbarHostState() }
    CompositionLocalProvider(LocalSnackbar provides snack) {
        androidx.compose.material3.Scaffold(snackbarHost = { SnackbarHost(snack) }, containerColor = MaterialTheme.colorScheme.surface,
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0)) { pad ->
            val fIn = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
            val fOut = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
            androidx.compose.animation.AnimatedContent(targetState = s.paired, label = "root",
                modifier = Modifier.padding(pad), transitionSpec = { fadeIn(fIn) togetherWith fadeOut(fOut) }) { paired ->
                if (paired) MainNav(startRoute) else Onboarding(pairingEnabled)
            }
        }
    }
}

@Composable
private fun MainNav(startRoute: String) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    val top = DESTS.firstOrNull { current?.startsWith(it.route) == true }?.route ?: when {
        current?.startsWith("send/") == true -> "sends"
        current?.startsWith("msg/") == true -> "feed"
        else -> "home"
    }
    val win = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    val wideDp = with(androidx.compose.ui.platform.LocalDensity.current) { win.width.toDp() } >= 600.dp
    NavigationSuiteScaffold(
        // A phone in landscape is ~360dp tall: a bottom bar would take a quarter of it, so it gets the rail too.
        layoutType = if (win.width > win.height || wideDp) NavigationSuiteType.NavigationRail else NavigationSuiteType.NavigationBar,
        navigationSuiteItems = {
            DESTS.forEach { d ->
                val sel = top == d.route
                item(
                    selected = sel,
                    // Re-selecting the current tab from a detail screen returns to that tab's list.
                    onClick = { if (sel) { if (current != d.route) nav.popBackStack(d.route, inclusive = false) } else nav.goTop(d.route) },
                    icon = { Sym(if (sel) d.selectedIcon else d.icon, null) },
                    label = { Text(stringResource(d.label), maxLines = 1) },
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        val fIn = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        val fOut = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
        SharedTransitionLayout {
            CompositionLocalProvider(LocalShared provides this) {
                NavHost(nav, startDestination = startRoute, modifier = Modifier.fillMaxSize(),
                    enterTransition = { fadeIn(fIn) }, exitTransition = { fadeOut(fOut) }) {
                    composable("home") { Scoped { HomeScreen(onOpenFeed = { nav.goTop("feed") }, onOpenSends = { nav.goTop("sends") }) } }
                    composable("feed") { Scoped { FeedScreen(onOpen = { nav.navigate("msg/$it") }) } }
                    composable("msg/{id}") { e -> Scoped { MessageScreen(e.arguments?.getString("id")?.toLongOrNull() ?: 0, onBack = { nav.popBackStack() }) } }
                    composable("sends") { Scoped { SendsScreen(onOpen = { nav.navigate("send/$it") }) } }
                    composable("send/{id}") { e -> Scoped { SendDetailScreen(e.arguments?.getString("id") ?: "", onBack = { nav.popBackStack() }) } }
                    composable("rules") { Scoped { RulesScreen() } }
                    composable("settings") { Scoped { SettingsScreen() } }
                }
            }
        }
    }
}

@Composable
private fun AnimatedVisibilityScope.Scoped(content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalAnimScope provides this) { content() }

private fun NavHostController.goTop(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

/** Shared-element bounds keyed by [key]; a no-op outside a SharedTransitionLayout (previews, tests). */
@Composable
fun Modifier.sharedCard(key: String): Modifier {
    val shared = LocalShared.current ?: return this
    val anim = LocalAnimScope.current ?: return this
    return with(shared) { this@sharedCard.sharedBounds(rememberSharedContentState(key), anim) }
}
