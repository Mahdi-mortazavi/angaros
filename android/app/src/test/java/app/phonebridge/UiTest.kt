package app.phonebridge

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.phonebridge.ui.BridgeTheme
import app.phonebridge.ui.Onboarding
import app.phonebridge.ui.SettingsScreen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Compose UI on the JVM (Robolectric): real screens, real Store/SharedPreferences, no device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class UiTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test @Config(qualifiers = "fa")
    fun onboardingInPersianWalksForwardAndBack() {
        rule.setContent { BridgeTheme(Settings(dynamicColor = false)) { Onboarding(pairingEnabled = false) } }
        rule.onNodeWithText("به رایانه‌ات اجازه بده برایت بخواند و بفرستد").assertExists()
        rule.onNodeWithText("مرحله‌ی ۱ از ۳").assertExists() // Persian digits follow the Persian UI by default
        rule.onNodeWithText("فعلاً رد شو").performClick() // permissions are not granted under Robolectric
        rule.onNodeWithText("در پس‌زمینه روشن بماند").assertExists()
        rule.onNodeWithText("بازگشت").performClick()
        rule.onNodeWithText("به رایانه‌ات اجازه بده برایت بخواند و بفرستد").assertExists()
    }

    /** 3.0.1 on the A30s: Constrained was a Box, so every dashboard section title sat under its own card. */
    @Test fun constrainedStacksTitleAboveBody() {
        rule.setContent { app.phonebridge.ui.Constrained { androidx.compose.material3.Text("TITLE"); androidx.compose.material3.Text("BODY") } }
        val title = rule.onNodeWithText("TITLE").fetchSemanticsNode().boundsInRoot
        val body = rule.onNodeWithText("BODY").fetchSemanticsNode().boundsInRoot
        assertTrue("body top ${body.top} must be below title bottom ${title.bottom}", body.top >= title.bottom)
    }

    /** Samsung's dynamic dark palette gave a light tertiaryContainer; the dark scheme must keep containers dark. */
    @Test fun darkSchemeContainersAreTheDarkerOfThePair() {
        val pale = androidx.compose.ui.graphics.Color(0xFFFFDFA0)
        val brown = androidx.compose.ui.graphics.Color(0xFF261A00)
        val fixed = app.phonebridge.ui.darkFixed(androidx.compose.material3.darkColorScheme(tertiaryContainer = pale, onTertiaryContainer = brown))
        assertTrue(fixed.tertiaryContainer == brown && fixed.onTertiaryContainer == pale)
        val ok = androidx.compose.material3.darkColorScheme()
        val same = app.phonebridge.ui.darkFixed(ok) // an already-correct scheme is untouched (ColorScheme has no equals)
        assertTrue(same.tertiaryContainer == ok.tertiaryContainer && same.primaryContainer == ok.primaryContainer && same.secondaryContainer == ok.secondaryContainer)
    }

    @Test @Config(qualifiers = "en")
    fun privacyToggleIsPersistedForTheSyncLayer() {
        // The same Store the screen writes through (Repo is a process singleton; Robolectric keeps statics across tests).
        val st = Repo.get(ctx).st
        st.setSyncAllowed("calls", true)
        rule.setContent { BridgeTheme(Settings(dynamicColor = false)) { SettingsScreen() } }
        val row = rule.onNodeWithText("Call log")
        row.performScrollTo().assertIsOn()
        row.performClick()
        rule.waitUntil(5_000) { !st.syncAllowed("calls") }
        assertFalse(st.syncAllowed("calls"))
        row.assertIsOff()
    }
}

/** Privacy purge on the real SQLite outbox: rows are matched by their leading "t" key, as every producer writes it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class OutboxPrivacyTest {
    @Test fun droppingAKindRemovesOnlyThatKind() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Db.get(ctx)
        // Built exactly like Sync.enqueueChunks / SmsReceiver build them.
        for (t in listOf("sms", "calls", "sms", "contacts", "status"))
            db.enqueue(org.json.JSONObject().put("t", t).put("items", org.json.JSONArray()).toString())
        db.enqueue(org.json.JSONObject().put("t", "result").put("text", "{\"t\":\"sms\",").toString()) // look-alike inside a value
        assertTrue(db.outboxDropKind("sms") == 2)
        assertTrue(db.outboxSize() == 4L)
        assertTrue(db.outboxDropKind("calls") == 1)
    }
}
