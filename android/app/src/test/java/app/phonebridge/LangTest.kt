package app.phonebridge

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.view.View
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.phonebridge.ui.BridgeTheme
import app.phonebridge.ui.Onboarding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** UI language: English first whatever the system locale; Persian flips strings and layout direction. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LangTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Before fun clean() { ctx.getSharedPreferences("bridge", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test @Config(qualifiers = "fa")
    fun freshInstallOnAPersianPhoneStartsInEnglish() {
        val st = Store(ctx)
        assertEquals(Lang.EN, st.language)
        val c = Lang.wrap(ctx, st.language)
        assertEquals("Let your PC read and send for you", c.getString(R.string.onb_perm_title))
        assertEquals(View.LAYOUT_DIRECTION_LTR, c.resources.configuration.layoutDirection)
    }

    @Test fun switchingToPersianFlipsStringsAndLayoutDirection() {
        val st = Store(ctx)
        st.language = Lang.FA
        assertEquals(Lang.FA, Store(ctx).language)
        val c = Lang.wrap(ctx, st.language)
        assertEquals("به رایانه‌ات اجازه بده برایت بخواند و بفرستد", c.getString(R.string.onb_perm_title))
        assertEquals(View.LAYOUT_DIRECTION_RTL, c.resources.configuration.layoutDirection)
    }

    @Test fun v300FollowTheSystemValueAndJunkFallBackToEnglish() {
        for (old in listOf("", "de", "en-US")) {
            ctx.getSharedPreferences("bridge", Context.MODE_PRIVATE).edit().putString("language", old).commit()
            assertEquals(old, Lang.EN, Store(ctx).language)
        }
        assertEquals(Lang.FA, Lang.normalize("fa-IR"))
    }

    @Test fun onboardingLanguageChooserStoresPersian() {
        val st = Repo.get(ctx).st
        rule.setContent { BridgeTheme(Settings(dynamicColor = false)) { Onboarding(pairingEnabled = false) } }
        rule.onNodeWithText("English").assertExists()
        rule.onNodeWithTag("lang_fa").performClick()
        rule.waitUntil(5_000) { st.language == Lang.FA }
    }

    @Test fun everyStringKeyExistsInBothLocales() {
        fun keys(dir: String): Map<String, Boolean> {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/$dir/strings.xml"))
            val n = doc.getElementsByTagName("string")
            return (0 until n.length).associate { val e = n.item(it) as org.w3c.dom.Element; e.getAttribute("name") to (e.getAttribute("translatable") != "false") }
        }
        val en = keys("values"); val fa = keys("values-fa")
        assertTrue(en.size > 100)
        assertEquals("missing in values-fa", emptySet<String>(), en.filterValues { it }.keys - fa.keys)
        assertEquals("missing in values", emptySet<String>(), fa.keys - en.keys)
    }
}

/** Update path: a bridge.db written by v2.1.0 (schema v1, no indexes) opens under the current Db with its data intact. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DbMigrationTest {
    // Robolectric keeps statics across tests: start from, and leave behind, no cached Db.
    private fun resetDb() = Db::class.java.getDeclaredField("inst").apply { isAccessible = true }.run { (get(null) as Db?)?.close(); set(null, null) }
    @Before fun before() = resetDb()
    @org.junit.After fun after() = resetDb()

    @Test fun v1DatabaseUpgradesKeepingAuditAndOutbox() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val f = ctx.getDatabasePath("bridge.db").apply { parentFile!!.mkdirs(); delete() }
        // The v2.1.0 schema: Db.onCreate minus the v2 indexes.
        SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
            db.execSQL("CREATE TABLE outbox (id INTEGER PRIMARY KEY AUTOINCREMENT, body TEXT NOT NULL)")
            db.execSQL("""CREATE TABLE audit (seq INTEGER PRIMARY KEY, ts INTEGER NOT NULL, id TEXT, agent TEXT, number TEXT,
                text TEXT, sim INTEGER, result TEXT NOT NULL, error TEXT, prev TEXT NOT NULL, hash TEXT NOT NULL)""")
            db.execSQL("CREATE TRIGGER audit_no_update BEFORE UPDATE ON audit BEGIN SELECT RAISE(ABORT, 'audit log is append-only'); END")
            db.execSQL("CREATE TRIGGER audit_no_delete BEFORE DELETE ON audit BEGIN SELECT RAISE(ABORT, 'audit log is append-only'); END")
            db.execSQL("""CREATE TABLE sends (id TEXT PRIMARY KEY, ts INTEGER, agent TEXT, number TEXT, text TEXT, sim INTEGER,
                parts INTEGER, sent_ok INTEGER DEFAULT 0, sent_fail INTEGER DEFAULT 0, err TEXT, finalized INTEGER DEFAULT 0,
                dlv_ok INTEGER DEFAULT 0, dlv_fail INTEGER DEFAULT 0, dlv_done INTEGER DEFAULT 0)""")
            db.execSQL("INSERT INTO outbox (body) VALUES ('{\"t\":\"sms\",\"items\":[]}')")
            db.version = 1
        }
        val db = Db.get(ctx)
        db.appendAudit("c1", "test", "+100", "hi", null, "sent", null) // the hash chain still works after the upgrade
        assertEquals(2, db.readableDatabase.version)
        assertEquals(1L, db.outboxSize())
        assertEquals(1L, db.auditCount())
        val idx = db.readableDatabase.rawQuery("SELECT name FROM sqlite_master WHERE type='index' AND name IN ('audit_by_id','sends_open')", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        assertEquals(setOf("audit_by_id", "sends_open"), idx.toSet())
    }
}
