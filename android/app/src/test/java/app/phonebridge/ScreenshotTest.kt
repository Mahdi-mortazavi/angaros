package app.phonebridge

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import app.phonebridge.ui.AppRoot
import app.phonebridge.ui.BridgeTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the real screens with SYNTHETIC data only (fictional +1 555 01xx numbers, invented names and texts) into
 * PNGs for the README and docs site. Opt-in: `./gradlew testDebugUnitTest --tests '*ScreenshotTest*' -PscreenshotDir=<dir>`.
 * Nothing here ever reads a real device: the SMS and contacts providers are fakes registered under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class, qualifiers = "en-w392dp-h850dp-xhdpi")
class ScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val out = System.getProperty("angaros.screenshots")

    @Before fun seed() {
        assumeTrue("set -PscreenshotDir to render screenshots", out != null)
        shadowOf(ctx).grantPermissions(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS)
        Robolectric.setupContentProvider(FakeSms::class.java, "sms")
        Robolectric.setupContentProvider(FakeContacts::class.java, "com.android.contacts")
        val st = Store(ctx)
        val now = System.currentTimeMillis()
        st.p.edit().putBoolean("sms.bfDone", true).putBoolean("calls.bfDone", true).putString("contacts.hashes", "{}")
            .putLong("sms.done", 1240).putLong("sms.total", 1240).putLong("lastPoll", now).putLong("lastUpload", now - 40_000)
            .putLong("lastSync", now - 40_000).apply()
        st.rulesMirror = JSONObject().put("ts", now).put("rules", JSONArray()
            .put(rule("otp-to-clipboard", "sms: class otp", "notify", true, now - 3_600_000, true, 41))
            .put(rule("bank-alert-telegram", "sms: bank deposit > 10,000,000", "webhook", true, now - 7_200_000, true, 12))
            .put(rule("nightly-digest", "cron 0 22 * * *", "llm summary -> append", true, now - 50_000_000, true, 30))
            .put(rule("missed-call-reply", "call: missed, known contact", "reply (dry-run first)", false, null, null, 0)))
        val db = Db.get(ctx)
        if (db.auditCount() == 0L) {
            db.appendAudit("c-7f3a", "claude-code", "+15550100", "Your table for two is confirmed for 8 pm.", 1, "sent", null)
            db.appendAudit("c-7f3a", "claude-code", "+15550100", "Your table for two is confirmed for 8 pm.", 1, "delivered", null)
            db.appendAudit("c-91b2", "hermes", "+15550142", "Reminder: team call moved to 15:30.", 1, "sent", null)
            db.appendAudit("c-c0de", "cursor", "+15550199", "Build finished, report is on your desk.", 2, "sent", null)
        }
    }

    private fun rule(name: String, trigger: String, action: String, enabled: Boolean, last: Long?, ok: Boolean?, runs: Int) =
        JSONObject().put("name", name).put("trigger", trigger).put("action", action).put("enabled", enabled)
            .put("last_run", last ?: JSONObject.NULL).put("last_ok", ok ?: JSONObject.NULL).put("runs", runs)

    private fun shoot(name: String, route: String, theme: String) {
        val s = Settings(paired = true, onboarded = true, dynamicColor = false, theme = theme)
        rule.setContent { BridgeTheme(s) { AppRoot(s, pairingEnabled = false, startRoute = route) } }
        rule.mainClock.advanceTimeBy(3_000)
        // The Sends screen verifies the audit chain on a background thread before it lists anything.
        rule.waitUntil(15_000) { rule.onAllNodesWithText("Verifying", substring = true).fetchSemanticsNodes().isEmpty() }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        File(out!!).apply { mkdirs() }.resolve("$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun home() = shoot("home", "home", "light")
    @Test fun homeDark() = shoot("home-dark", "home", "dark")
    @Test fun feed() = shoot("messages", "feed", "light")
    @Test fun feedDark() = shoot("messages-dark", "feed", "dark")
    @Test fun sends() = shoot("sends", "sends", "light")
    @Test fun rules() = shoot("automations", "rules", "light")
    @Test fun settings() = shoot("settings", "settings", "light")
}

/** content://sms with a fixed synthetic inbox. Supports the three query shapes Repo uses. */
class FakeSms : ContentProvider() {
    private val now = System.currentTimeMillis()
    private val min = 60_000L
    // _id, type (1 inbox, 2 sent), address, body, date, read
    private val rows = listOf(
        arrayOf<Any>(9, 1, "ACME", "Your Acme sign-in code is 482913. It expires in 5 minutes. Never share it.", now - 2 * min, 0),
        arrayOf<Any>(8, 1, "+15550100", "Running 10 minutes late, grab us a table?", now - 18 * min, 0),
        arrayOf<Any>(7, 2, "+15550100", "Sure, see you there!", now - 16 * min, 1),
        arrayOf<Any>(6, 1, "DemoPost", "Your parcel EX0000123 is out for delivery today between 14:00 and 16:00.", now - 55 * min, 1),
        arrayOf<Any>(5, 1, "+98700000", "بانک ملت\nواریز: 25,000,000 ریال\nمانده: 41,500,000 ریال", now - 95 * min, 1),
        arrayOf<Any>(4, 1, "+15550142", "Can you send me the slides before the call?", now - 3 * 60 * min, 1),
        arrayOf<Any>(3, 1, "ShopDemo", "WEEKEND SALE: 30% off everything until Sunday. Reply STOP to opt out.", now - 5 * 60 * min, 1),
        arrayOf<Any>(2, 1, "BANKDEMO", "Card ****0000: purchase 42.50 USD at Example Coffee. Balance 1,204.10 USD.", now - 26 * 60 * min, 1),
        arrayOf<Any>(1, 1, "+15550199", "Thanks for yesterday, it was great to catch up!", now - 30 * 60 * min, 1),
    )
    private val cols = listOf("_id", "type", "address", "body", "date", "read")

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val proj = projection ?: cols.toTypedArray()
        val picked = when {
            selection?.contains("_id = ?") == true -> rows.filter { it[0].toString() == args!![0] }
            selection?.contains("date < ?") == true -> emptyList() // one page is the whole synthetic inbox
            selection?.contains("date >= ?") == true -> rows.filter { (it[4] as Long) >= args!![0].toLong() }
            selection?.contains("type IN (1,2)") == true || selection == null -> rows
            else -> emptyList()
        }.sortedByDescending { it[4] as Long }
        return MatrixCursor(proj).apply { picked.forEach { r -> addRow(proj.map { r[cols.indexOf(it)] }) } }
    }

    override fun onCreate() = true
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}

/** PhoneLookup for the synthetic contacts. */
class FakeContacts : ContentProvider() {
    private val names = mapOf("+15550100" to "Alex Demo", "+15550142" to "Sam Example", "+15550199" to "Robin Sample")

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val c = MatrixCursor(projection ?: arrayOf("display_name"))
        names[uri.lastPathSegment]?.let { n -> c.addRow(Array(c.columnCount) { n }) }
        return c
    }

    override fun onCreate() = true
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
