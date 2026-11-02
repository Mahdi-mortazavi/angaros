package app.phonebridge

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.TimeZone

/**
 * Parity with the PC parser: shared/parse-vectors.json is test_parse.py's corpus with parse.py's answers
 * (pc/genparsevec.py). A divergence fails with the list of differing messages.
 */
class ParseTest {
    private val vectors = JSONArray(File(System.getProperty("user.dir"), "../../shared/parse-vectors.json").readText(Charsets.UTF_8))

    @Test fun classifierMatchesPythonOnTheWholeCorpus() {
        val diffs = mutableListOf<String>()
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val r = Parse.classify(v.getString("body"), v.getString("sender"), v.getBoolean("known"))
            val want = v.getString("cls").uppercase()
            val wantOtp = if (v.isNull("otp")) null else v.getString("otp")
            if (r.cls.name != want) diffs += "#$i cls ${r.cls} != $want: ${v.getString("body").take(40)}"
            if (r.otp != wantOtp) diffs += "#$i otp ${r.otp} != $wantOtp: ${v.getString("body").take(40)}"
            if (want == "BANK") {
                if (r.amountRial != v.getLong("amount")) diffs += "#$i amount ${r.amountRial} != ${v.getLong("amount")}"
                if (r.direction != v.getInt("direction")) diffs += "#$i direction ${r.direction} != ${v.getInt("direction")}"
            }
        }
        assertTrue("${vectors.length()} vectors, ${diffs.size} diffs:\n" + diffs.joinToString("\n"), diffs.isEmpty())
        assertTrue(vectors.length() >= 60)
    }

    /** A year sitting closer to the keyword than the code must lose (parse.py gives the same answers). */
    @Test fun yearsLoseToCodes() {
        assertEquals("4417", Parse.otp("Your code for 2025 login: 4417"))
        assertEquals("55821", Parse.otp("کد سال 1403 ثبت شد. رمز: 55821"))
    }

    @Test fun normalisation() {
        assertEquals("کتاب علی 123", Parse.text("كتاب علي ۱۲۳"))
        for (n in listOf("+989000000101", "00989000000101", "989000000101", "9000000101", "۰۹۰۰ ۰۰۰ ۰۱۰۱"))
            assertEquals(n, "09000000101", Parse.number(n))
        assertEquals("mci", Parse.number("MCI"))
    }

    @Test fun jalaliKnownDates() {
        assertEquals(Jalali.Date(1403, 1, 1), Jalali.fromGregorian(2024, 3, 20))
        assertEquals(Jalali.Date(1403, 2, 11), Jalali.fromGregorian(2024, 4, 30))
        assertEquals(Jalali.Date(1405, 8, 11), Jalali.fromGregorian(2026, 11, 2))
        assertEquals(Jalali.Date(1399, 12, 30), Jalali.fromGregorian(2021, 3, 20)) // leap Esfand
        assertEquals(Jalali.Date(1400, 1, 1), Jalali.fromGregorian(2021, 3, 21))
    }

    @Test fun formatting() {
        val tehran = TimeZone.getTimeZone("Asia/Tehran")
        val fa = Fmt(persianDigits = true, jalali = true, fa = true, tz = tehran)
        val en = Fmt(persianDigits = false, jalali = false, fa = false, tz = tehran)
        val ms = 1793573400000L // 2026-11-01T22:50Z = 2026-11-02 02:20 Tehran (UTC+3:30, no DST since 2022)
        assertEquals("۱۱ آبان ۱۴۰۵", fa.date(ms))
        assertEquals("2 Nov 2026", en.date(ms))
        assertEquals("۰۲:۲۰", fa.time(ms))
        assertEquals("۱۲٬۳۴۵", fa.num(12345))
        assertEquals("12,345", en.num(12345))
        assertEquals("۱۵۰٬۰۰۰", fa.toman(1_500_000))
        assertEquals("02:20", en.stamp(ms, now = ms + 60_000))
        assertEquals("2 Nov 2026, 02:20", en.stamp(ms, now = ms + 86_400_000L * 3))
    }
}
