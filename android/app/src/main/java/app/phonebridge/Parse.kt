package app.phonebridge

import java.util.Calendar
import java.util.TimeZone

/**
 * On-phone SMS understanding for the Activity feed and today's counters. A compact port of pc/parse.py
 * (same keywords, same precedence); the PC stays the authority for anything agents read.
 */
object Parse {
    enum class Cls { OTP, BANK, DELIVERY, AD, PERSONAL, SERVICE }

    /** direction: +1 money in, -1 money out, 0 unknown. amountRial null when no amount was found. */
    data class Result(val cls: Cls, val otp: String? = null, val amountRial: Long? = null, val direction: Int = 0)

    private val DIGIT_MAP = "۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩".zip("01234567890123456789").toMap()
    private val CHAR_MAP = mapOf('ي' to "ی", 'ى' to "ی", 'ك' to "ک", 'ة' to "ه", 'ۀ' to "ه", 'أ' to "ا", 'إ' to "ا",
        'ٱ' to "ا", '‌' to " ", '‍' to "", 'ـ' to "", '٬' to ",", '،' to ",", '٫' to ".")
    private val DIACRITICS = Regex("[ً-ٰٟ]")
    private const val L = "A-Za-z؀-ۿ"
    private const val B = "(?<![$L])"
    private const val E = "(?![$L])"

    fun digits(s: String) = buildString(s.length) { s.forEach { append(DIGIT_MAP[it] ?: it) } }

    /** Same as parse.text(): unified letters/digits, ZWNJ -> space, no diacritics, lower-case, single spaces. */
    fun text(s: String): String {
        val d = buildString(s.length) { digits(s).forEach { c -> append(CHAR_MAP[c] ?: c.toString()) } }
        return DIACRITICS.replace(d, "").lowercase().replace(Regex("\\s+"), " ").trim()
    }

    /** parse.number(): +98 / 0098 / 98 / 9xx mobiles -> 09xx; alphanumeric senders -> lower-case name. */
    fun number(n: String): String {
        val d = digits(n).replace(Regex("[^\\d+]"), "")
        if (!d.any { it.isDigit() }) return text(n).replace(" ", "")
        for (pre in listOf("+98", "0098")) if (d.startsWith(pre)) {
            val rest = d.removePrefix(pre)
            return if (rest.length == 10) "0$rest" else rest
        }
        val x = d.trimStart('+')
        if (x.startsWith("98") && x.length == 12) return "0" + x.substring(2)
        if (x.startsWith("9") && x.length == 10) return "0$x"
        return x
    }

    fun isMobile(n: String) = Regex("09\\d{9}").matches(number(n))

    // ---- OTP (parse.otp) ----
    private val OTP_HINT = Regex("otp|code|passcode|password|pass\\b|pin\\b|verif|one[- ]time|<#>|رمز|کد|کلمه عبور|یکبار|تایید|تأیید|ورود|احراز", RegexOption.IGNORE_CASE)
    private val STRONG_OTP = Regex("otp|passcode|password|verif|one[- ]time|<#>|\\bcode\\b|رمز|یکبار|تایید|تأیید|کد ورود|احراز|کلمه عبور", RegexOption.IGNORE_CASE)
    private val OTP_RX = Regex("(?<![\\d,./:#-])(\\d{4,8})(?![\\d,]|\\s*(?:ریال|تومان|تومن|rial|toman|irr)|[/:.-]\\d)", RegexOption.IGNORE_CASE)
    private val NOT_OTP_BEFORE = Regex("(رهگیری|پیگیری|سفارش|order|tracking|مبلغ|مانده|موجودی|حساب|کارت|card|acc|amount|balance|" +
        "شماره|tel|تلفن|شعبه|branch|ملی|پستی|اشتراک|شناسه|تخفیف|discount|promo|پشتیبانی)\\s*[:：]?\\s*$", RegexOption.IGNORE_CASE)
    private val YEAR = Regex("(?:13|14|19|20)\\d\\d")

    fun otp(body: String): String? {
        var t = text(body)
        val hints = OTP_HINT.findAll(t).map { it.range.last + 1 }.toList()
        if (hints.isEmpty()) return null
        t = Regex("\\b[a-z]-(\\d{4,8})\\b").replace(t) { " " + it.groupValues[1] }
        t = Regex("(?<![\\d/:.,-])(\\d{3})[- ](\\d{3})(?![\\d/:.,-])").replace(t) { it.groupValues[1] + it.groupValues[2] }
        var best: Pair<Int, String>? = null
        for (m in OTP_RX.findAll(t)) {
            val start = m.range.first
            if (NOT_OTP_BEFORE.containsMatchIn(t.substring(maxOf(0, start - 25), start))) continue
            val before = hints.filter { it <= start }.map { start - it }
            var dist = before.minOrNull() ?: (1000 + hints.minOf { kotlin.math.abs(it - start) })
            if (YEAR.matches(m.groupValues[1])) dist += 500
            if (best == null || dist < best.first) best = dist to m.groupValues[1]
        }
        return best?.second
    }

    // ---- bank (a reduced parse.bank: detects a transaction and its amount/direction) ----
    private val LAGHV = Regex("لغو\\s*11")
    private const val BANK_NAMES = "ملی|ملت|سامان|پارسیان|پاسارگاد|تجارت|سپه|کشاورزی|رفاه|مسکن|بلو|صادرات|آینده|رسالت|اقتصاد ?نوین|" +
        "پست ?بانک|کارآفرین|خاورمیانه|گردشگری|ایران ?زمین|melli|mellat|saman|parsian|pasargad|tejarat|sepah|keshavarzi|refah|maskan|blu|saderat|ayandeh"
    private val BANKISH = Regex("بانک|bank|مانده|موجودی|balance|$B(?:$BANK_NAMES)$E", RegexOption.IGNORE_CASE)
    private const val UNIT = "(?:\\s*(ریال|تومان|تومن|rial|irr|toman))?"
    private const val NUM = "[+-]?\\s?\\d{1,3}(?:,\\d{3})+|[+-]?\\s?\\d{4,}"
    private const val AMT = "(?<![\\d/.,\\-])($NUM)(?![\\d]|[.\\-/:]\\d)$UNIT"
    private val LABELED = Regex("$B(?:مبلغ|واریز|برداشت|انتقال|خرید|پرداخت|amount|deposit|withdrawal|withdraw|purchase|payment)\\s*[:：]?\\s*$AMT", RegexOption.IGNORE_CASE)
    private val ANY_AMT = Regex(AMT, RegexOption.IGNORE_CASE)
    private val BAL = Regex("$B(?:مانده|موجودی|balance|bal)$E[^\\d\\n]{0,25}?$AMT", RegexOption.IGNORE_CASE)
    private val DESC = Regex("(?m)^\\s*(?:شرح|بابت|توضیحات|description)[^\\n]*|(?:شرح|بابت|توضیحات|description)\\s*[:：][^\\n]*", RegexOption.IGNORE_CASE)
    private val TYPES = listOf(
        1 to Regex("واریز|بستانکار|deposit|credit", RegexOption.IGNORE_CASE),
        -1 to Regex("خرید|پرداخت|purchase|payment|pos\\b", RegexOption.IGNORE_CASE),
        -2 to Regex("انتقال|کارت ?به ?کارت|حواله|پایا|ساتنا|transfer", RegexOption.IGNORE_CASE),
        -1 to Regex("برداشت|بدهکار|خودپرداز|عابر ?بانک|atm|withdraw|debit", RegexOption.IGNORE_CASE),
    )
    private val INCOMING = Regex("به (?:حساب|کارت) شما|انتقال به شما|واریز|بستانکار|to your", RegexOption.IGNORE_CASE)

    /** (amountRial, direction) when [body] is a bank transaction SMS, else null. */
    fun bank(body: String): Pair<Long, Int>? {
        val raw = buildString { digits(body).forEach { c -> append(CHAR_MAP[c] ?: c.toString()) } }
        val t = text(body)
        if (t.isEmpty() || LAGHV.containsMatchIn(t) || !BANKISH.containsMatchIn(t)) return null
        val bal = BAL.find(raw)
        val taken = bal?.range ?: IntRange.EMPTY
        val m = LABELED.findAll(raw).firstOrNull { it.groups[1]!!.range.first !in taken }
            ?: ANY_AMT.findAll(raw).firstOrNull { it.groups[1]!!.range.first !in taken && Regex("[,+-]").containsMatchIn(it.groupValues[1]) }
            ?: return null
        val amountRaw = m.groupValues[1].trim()
        val sign = when { amountRaw.startsWith("-") -> -1; amountRaw.startsWith("+") -> 1; else -> 0 }
        val tt = DESC.replace(raw, "").lowercase()
        var dir = TYPES.firstOrNull { it.second.containsMatchIn(tt) }?.first
        if (dir == null && sign == 0) return null
        if (dir == -2) dir = if (INCOMING.containsMatchIn(tt)) 1 else -1
        if (sign != 0) dir = sign
        val unit = m.groupValues[2].ifEmpty { if (Regex("تومان|تومن|toman").containsMatchIn(t)) "تومان" else "" }
        val v = amountRaw.filter { it.isDigit() }.toLongOrNull() ?: return null
        return (if (unit.lowercase() in setOf("تومان", "تومن", "toman")) v * 10 else v) to (dir ?: 0)
    }

    private val DELIVERY = Regex("مرسوله|بسته شما|تحویل|ارسال شد|پیک|سفارش|کد رهگیری|رهگیری|پست|tracking|delivered|shipped|order|courier", RegexOption.IGNORE_CASE)
    private val AD = Regex("تخفیف|جشنواره|حراج|ویژه|هدیه|قرعه ?کشی|عضویت|ثبت ?نام کنید|کلیک کنید|\\d+ ?%|٪|discount|sale|offer|promo|https?://|www\\.", RegexOption.IGNORE_CASE)

    /** parse.classify(): same precedence (ad opt-out marker, otp, bank, delivery, ad, personal, service). */
    fun classify(body: String, sender: String, knownContact: Boolean = false): Result {
        val t = text(body)
        if (LAGHV.containsMatchIn(t)) return Result(Cls.AD)
        val code = otp(body)
        val b = bank(body)
        return when {
            code != null && (STRONG_OTP.containsMatchIn(t) || b == null) -> Result(Cls.OTP, otp = code)
            b != null -> Result(Cls.BANK, amountRial = b.first, direction = b.second)
            DELIVERY.containsMatchIn(t) && !isMobile(sender) -> Result(Cls.DELIVERY)
            AD.containsMatchIn(t) && !knownContact -> Result(Cls.AD)
            knownContact || isMobile(sender) -> Result(Cls.PERSONAL)
            else -> Result(Cls.SERVICE)
        }
    }
}

/** Jalali (Solar Hijri) calendar conversion, the standard arithmetic algorithm (valid 1-3177 SH). */
object Jalali {
    data class Date(val year: Int, val month: Int, val day: Int)

    fun fromGregorian(gy: Int, gm: Int, gd: Int): Date {
        val gdm = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 355666 + 365 * gy + (gy2 + 3) / 4 - (gy2 + 99) / 100 + (gy2 + 399) / 400 + gd + gdm[gm - 1]
        var jy = -1595 + 33 * (days / 12053)
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) { jy += (days - 1) / 365; days = (days - 1) % 365 }
        val jm = if (days < 186) 1 + days / 31 else 7 + (days - 186) / 30
        val jd = 1 + if (days < 186) days % 31 else (days - 186) % 30
        return Date(jy, jm, jd)
    }

    fun fromMillis(ms: Long, tz: TimeZone = TimeZone.getDefault()): Date {
        val c = Calendar.getInstance(tz).apply { timeInMillis = ms }
        return fromGregorian(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    val MONTHS_FA = listOf("فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور", "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند")
    val MONTHS_EN = listOf("Farvardin", "Ordibehesht", "Khordad", "Tir", "Mordad", "Shahrivar", "Mehr", "Aban", "Azar", "Dey", "Bahman", "Esfand")
}

/** Display formatting that follows the owner's digit and calendar choices. Pure, so it is unit-tested. */
data class Fmt(val persianDigits: Boolean, val jalali: Boolean, val fa: Boolean, val tz: TimeZone = TimeZone.getDefault()) {
    fun digits(s: String): String = if (!persianDigits) s else buildString(s.length) {
        s.forEach { append(if (it in '0'..'9') '۰' + (it - '0') else it) }
    }

    fun num(n: Long): String = digits(String.format(java.util.Locale.US, "%,d", n).let { if (persianDigits) it.replace(',', '٬') else it })
    fun num(n: Int) = num(n.toLong())

    fun time(ms: Long): String {
        val c = Calendar.getInstance(tz).apply { timeInMillis = ms }
        return digits("%02d:%02d".format(java.util.Locale.US, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)))
    }

    /** "12 Aban 1405" / "۱۲ آبان ۱۴۰۵" in Jalali, else "2 Nov 2026" (month names follow the UI language). */
    fun date(ms: Long): String {
        if (jalali) {
            val d = Jalali.fromMillis(ms, tz)
            return digits("${d.day} ${(if (fa) Jalali.MONTHS_FA else Jalali.MONTHS_EN)[d.month - 1]} ${d.year}")
        }
        val c = Calendar.getInstance(tz).apply { timeInMillis = ms }
        val months = if (fa) listOf("ژانویه", "فوریه", "مارس", "آوریل", "مه", "ژوئن", "ژوئیه", "اوت", "سپتامبر", "اکتبر", "نوامبر", "دسامبر")
            else listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        return digits("${c.get(Calendar.DAY_OF_MONTH)} ${months[c.get(Calendar.MONTH)]} ${c.get(Calendar.YEAR)}")
    }

    /** Time only for today, else date + time. */
    fun stamp(ms: Long, now: Long = System.currentTimeMillis()): String =
        if (startOfDay(now) == startOfDay(ms)) time(ms) else date(ms) + (if (fa) "، " else ", ") + time(ms)

    fun startOfDay(ms: Long): Long = Calendar.getInstance(tz).apply {
        timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Toman with grouping, the unit Iranians read amounts in. */
    fun toman(rial: Long) = num(rial / 10)
}
