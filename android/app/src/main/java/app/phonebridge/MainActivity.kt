package app.phonebridge

import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.phonebridge.ui.AppRoot
import app.phonebridge.ui.BridgeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class App : Application(), androidx.work.Configuration.Provider {
    // On-demand WorkManager init (its startup initializer is removed in the manifest).
    override val workManagerConfiguration get() = androidx.work.Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        Lang.syncOnStart(this, Store(this))
        if (BuildConfig.DEBUG) {
            // Debug builds log every main-thread disk/network access and leaked closeable.
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().detectLeakedSqlLiteObjects()
                .detectActivityLeaks().penaltyLog().build())
        }
    }
}

/** Single activity: splash -> onboarding until paired -> adaptive navigation. All UI is Compose. */
class MainActivity : ComponentActivity() {
    /**
     * Debug builds only, for screenshots on the live phone without touching system settings:
     * extras set_theme (light|dark|system), set_lang (en|fa) and route (home|feed|sends|rules|settings).
     */
    private fun debugExtras() {
        val repo = Repo.get(applicationContext)
        lifecycleScope.launch {
            intent.getStringExtra("set_theme")?.let { repo.setTheme(it) }
            intent.getStringExtra("set_lang")?.let { repo.setLanguage(it) }
        }
    }

    private val vm: AppViewModel by viewModels { viewModelFactory { initializer { AppViewModel(Repo.get(applicationContext)) } } }

    /**
     * The stored language (English by default, whatever the system locale) wraps the activity, except on 13+ once
     * the system holds a per-app locale (LocaleManager applies that itself, including picks in system settings).
     */
    override fun attachBaseContext(base: Context) {
        val sysPerApp = Build.VERSION.SDK_INT >= 33 && base.getSystemService(LocaleManager::class.java)?.applicationLocales?.isEmpty == false
        super.attachBaseContext(if (sysPerApp) base else Lang.wrap(base, Store(base).language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) debugExtras()
        // A view's run queue drains after the first traversal: the service's onCreate (main thread) and WorkManager's
        // init no longer compete with the first frame.
        window.decorView.post {
            lifecycleScope.launch(Dispatchers.IO) {
                if (Store(applicationContext).pairing != null) { BridgeWorker.schedule(applicationContext); BridgeService.start(applicationContext) }
            }
        }
        setContent {
            // Seeded synchronously (prefs are already in memory), so the first frame is the real UI: no splash hold.
            val s by vm.settings.collectAsStateWithLifecycle()
            // Debug-only: `am start ... --ez preview_onboarding true` shows the wizard without touching the pairing
            // (used for screenshots on the live, paired phone).
            val preview = BuildConfig.DEBUG && intent.getBooleanExtra("preview_onboarding", false)
            val route = if (BuildConfig.DEBUG) intent.getStringExtra("route") ?: "home" else "home"
            BridgeTheme(s) { AppRoot(if (preview) s.copy(paired = false) else s, pairingEnabled = !preview, startRoute = route) }
        }
    }
}
