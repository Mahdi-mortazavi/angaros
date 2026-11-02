"""Local SMS understanding: text/number normalisation, OTP extraction, Iranian bank SMS parsing, classification.
Pure functions, no I/O. Run `python parse.py` for the self-check (test_parse.py has the full suite)."""
import re

# ---------- normalisation ----------
DIGITS = str.maketrans("۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩", "01234567890123456789")
_CHARS = str.maketrans({"ي": "ی", "ى": "ی", "ك": "ک", "ة": "ه", "ۀ": "ه", "أ": "ا", "إ": "ا", "ٱ": "ا",
                        "‌": " ", "‍": "", "ـ": "", "٬": ",", "،": ",", "٫": "."})
_DIACRITICS = re.compile(r"[ً-ٰٟ]")
_L = "A-Za-z؀-ۿ"
B, E = rf"(?<![{_L}])", rf"(?![{_L}])"  # word boundaries that also work for Persian letters


def digits(s):
    return (s or "").translate(DIGITS)


def text(s):
    """Search form: Persian/Arabic letters + digits unified, ZWNJ -> space, diacritics/tatweel gone, lower-case."""
    s = _DIACRITICS.sub("", digits(s).translate(_CHARS)).lower()
    return re.sub(r"\s+", " ", s).strip()


def joined(s):
    """`text` with ZWNJ removed instead of spaced: 'می‌خواهم' -> 'میخواهم' (indexed too, so both spellings match)."""
    return text((s or "").replace("‌", ""))


def number(n):
    """Sender key: +98 / 0098 / 98 / 9xx / 09xx mobiles all become 09xx...; short codes keep their digits
    (with or without +98); alphanumeric senders ('MCI', 'Snapp') become their lower-case name."""
    d = re.sub(r"[^\d+]", "", digits(n))
    if not re.search(r"\d", d):
        return re.sub(r"\s+", "", text(n))
    for pre in ("+98", "0098"):
        if d.startswith(pre):
            rest = d[len(pre):]
            return "0" + rest if len(rest) == 10 else rest
    d = d.lstrip("+")
    if d.startswith("98") and len(d) == 12: return "0" + d[2:]
    if d.startswith("9") and len(d) == 10: return "0" + d
    return d


def is_mobile(n):
    return bool(re.fullmatch(r"09\d{9}", number(n)))


# ---------- Jalali dates (for --since 1403-02-11) ----------
def jalali_to_gregorian(jy, jm, jd):
    jy += 1595
    days = -355668 + 365 * jy + (jy // 33) * 8 + ((jy % 33) + 3) // 4 + jd + ((jm - 1) * 31 if jm < 7 else (jm - 7) * 30 + 186)
    gy = 400 * (days // 146097)
    days %= 146097
    if days > 36524:
        days -= 1
        gy += 100 * (days // 36524)
        days %= 36524
        if days >= 365: days += 1
    gy += 4 * (days // 1461)
    days %= 1461
    if days > 365:
        gy += (days - 1) // 365
        days = (days - 1) % 365
    gd = days + 1
    months = [31, 29 if (gy % 4 == 0 and gy % 100 != 0) or gy % 400 == 0 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
    gm = 0
    while gm < 12 and gd > months[gm]:
        gd -= months[gm]
        gm += 1
    return gy, gm + 1, gd


# ---------- OTP ----------
OTP_HINT = re.compile(r"otp|code|passcode|password|pass\b|pin\b|verif|one[- ]time|<#>|رمز|کد|کلمه عبور|یکبار|تایید|تأیید|ورود|احراز", re.I)
STRONG_OTP = re.compile(r"otp|passcode|password|verif|one[- ]time|<#>|\bcode\b|رمز|یکبار|تایید|تأیید|کد ورود|احراز|کلمه عبور", re.I)
_OTP = re.compile(r"(?<![\d,./:#-])(\d{4,8})(?![\d,]|\s*(?:ریال|تومان|تومن|rial|toman|irr)|[/:.-]\d)", re.I)
_NOT_OTP_BEFORE = re.compile(r"(رهگیری|پیگیری|سفارش|order|tracking|مبلغ|مانده|موجودی|حساب|کارت|card|acc|amount|balance|"
                             r"شماره|tel|تلفن|شعبه|branch|ملی|پستی|اشتراک|شناسه|تخفیف|discount|promo|پشتیبانی)\s*[:：]?\s*$", re.I)
_YEAR = re.compile(r"(?:13|14|19|20)\d\d")


def otp(body):
    """Best OTP code in an SMS, or None. Picks the 4-8 digit run nearest after an OTP keyword."""
    t = text(body)
    hints = [m.end() for m in OTP_HINT.finditer(t)]
    if not hints:
        return None
    t = re.sub(r"\b[a-z]-(\d{4,8})\b", r" \1", t)                          # G-482913
    t = re.sub(r"(?<![\d/:.,-])(\d{3})[- ](\d{3})(?![\d/:.,-])", r"\1\2", t)  # 123-456 / 123 456
    best = None
    for m in _OTP.finditer(t):
        if _NOT_OTP_BEFORE.search(t[max(0, m.start() - 25):m.start()]):
            continue
        # distance to the closest keyword before the code (codes after the keyword win), else after it
        before = [m.start() - h for h in hints if h <= m.start()]
        dist = min(before) if before else 1000 + min(abs(h - m.start()) for h in hints)
        if _YEAR.fullmatch(m.group(1)): dist += 500  # '2024' / '1403' are usually years
        if best is None or dist < best[0]:
            best = (dist, m.group(1))
    return best[1] if best else None


# ---------- bank ----------
BANKS = [("melli", r"ملی|melli"), ("mellat", r"ملت|mellat"), ("saman", r"سامان|saman"), ("parsian", r"پارسیان|parsian"),
         ("pasargad", r"پاسارگاد|pasargad"), ("tejarat", r"تجارت|tejarat"), ("sepah", r"سپه|sepah"),
         ("keshavarzi", r"کشاورزی|keshavarzi|agri"), ("refah", r"رفاه|refah"), ("maskan", r"مسکن|maskan"),
         ("blu", r"بلو|blu"), ("saderat", r"صادرات|saderat"), ("ayandeh", r"آینده|ayandeh"), ("resalat", r"رسالت|resalat"),
         ("shahr", r"شهر|shahr"), ("en", r"اقتصاد ?نوین|eghtesad"), ("sina", r"سینا|sina"), ("dey", r"دی|dey"),
         ("postbank", r"پست ?بانک|postbank"), ("tosee", r"توسعه (?:تعاون|صادرات)"), ("mehr", r"مهر ?ایران|mehr"),
         ("karafarin", r"کارآفرین|کارافرین|karafarin"), ("middleeast", r"خاورمیانه"), ("gardeshgari", r"گردشگری"),
         ("iranzamin", r"ایران ?زمین"), ("sarmayeh", r"سرمایه"), ("ansar", r"انصار")]
_BANK_PREFIXED = [(k, re.compile(rf"(?:بانک|bank)\s*(?:{p}){E}", re.I)) for k, p in BANKS]
_BANK_ALONE = [(k, re.compile(rf"{B}(?:{p}){E}", re.I)) for k, p in BANKS if k not in ("dey", "shahr", "sina", "mehr")]
_UNIT = r"(?:\s*(ریال|تومان|تومن|rial|irr|toman))?"
_NUM = r"[+-]?\s?\d{1,3}(?:,\d{3})+|[+-]?\s?\d{4,}"
_AMT = rf"(?<![\d/.,\-])({_NUM})(?![\d]|[.\-/:]\d){_UNIT}"   # never a piece of 21.8000.123 / 849-800-123 / dates
_LABELED_AMT = re.compile(rf"{B}(?:مبلغ|واریز|برداشت|انتقال|خرید|پرداخت|amount|deposit|withdrawal|withdraw|purchase|payment)"
                          rf"\s*[:：]?\s*{_AMT}", re.I)
_ANY_AMT = re.compile(_AMT, re.I)
_BAL = re.compile(rf"{B}(?:مانده|موجودی|balance|bal){E}[^\d\n]{{0,25}}?{_AMT}", re.I)
_ACCT = re.compile(rf"{B}(?:کارت|حساب|سپرده|از|به|card|acc|account|from|to){E}\s*[:：]?\s*([.*xX]*(?:[\d*xX]+[ .\-]?){{1,8}})", re.I)
_MERCH = re.compile(rf"{B}(?:خرید از|پذیرنده|فروشگاه|بابت|شرح|توضیحات|merchant|at){E}\s*[:：]?\s*([^\n\d:：]{{2,40}})", re.I)
_DESC_LINE = re.compile(r"(?m)^\s*(?:شرح|بابت|توضیحات|description)[^\n]*|(?:شرح|بابت|توضیحات|description)\s*[:：][^\n]*", re.I)
_DATE = re.compile(r"(\d{2,4}/\d{1,2}/\d{1,2}(?:[\s_-]+\d{1,2}:\d{2}(?::\d{2})?)?|\d{4}[-_]\d{1,2}:\d{2})")
_TYPES = [  # (type, direction, keywords) - first match wins, order matters
    ("deposit", 1, r"واریز|بستانکار|deposit|credit"),
    ("payment", -1, r"خرید|پرداخت|purchase|payment|pos\b"),
    ("transfer", -1, r"انتقال|کارت ?به ?کارت|حواله|پایا|ساتنا|transfer"),
    ("withdraw", -1, r"برداشت|بدهکار|خودپرداز|عابر ?بانک|atm|withdraw|debit"),
]
_INCOMING = re.compile(r"به (?:حساب|کارت) شما|انتقال به شما|واریز|بستانکار|to your", re.I)
LAGHV = re.compile(r"لغو\s*11")  # legally required opt-out marker on Iranian advertising SMS


def _rial(raw, unit):
    v = int(re.sub(r"[^\d]", "", raw))
    return v * 10 if (unit or "").lower() in ("تومان", "تومن", "toman") else v


def which_bank(t):
    for k, rx in _BANK_PREFIXED:
        if rx.search(t): return k
    hits = [(m.start(), k) for k, rx in _BANK_ALONE for m in [rx.search(t)] if m]
    return min(hits)[1] if hits else "bank"


def bank(body):
    """Parse an Iranian bank transaction SMS. Returns a dict or None if this is not a transaction SMS.
    amount/balance are in Rial (amount_toman = amount // 10)."""
    raw = digits(body or "").translate(_CHARS)
    t = text(body)
    if not t or LAGHV.search(t):
        return None
    name = which_bank(t)
    if name == "bank" and not re.search(r"بانک|bank|مانده|موجودی|balance", t, re.I):
        return None
    bal = _BAL.search(raw)
    taken = range(bal.start(), bal.end()) if bal else range(0)
    m = next((x for x in _LABELED_AMT.finditer(raw) if x.start(1) not in taken), None) or \
        next((x for x in _ANY_AMT.finditer(raw) if x.start(1) not in taken and re.search(r"[,+-]", x.group(1))), None)
    if not m:
        return None  # bare unlabeled digit runs are too ambiguous to call an amount
    amount_raw, unit = m.group(1), m.group(2)
    sign = -1 if amount_raw.strip().startswith("-") else (1 if amount_raw.strip().startswith("+") else 0)
    tt = _DESC_LINE.sub("", raw).lower()  # a description like 'شرح: واریز به ...' must not decide the type
    kind, direction = next(((k, d) for k, d, rx in _TYPES if re.search(rx, tt, re.I)), (None, 0))
    if kind is None and sign:
        kind, direction = ("deposit", 1) if sign > 0 else ("withdraw", -1)
    if kind is None:
        return None
    if sign:
        direction = sign
        if kind == "deposit" and sign < 0: kind = "withdraw"
    elif kind == "transfer" and _INCOMING.search(tt):
        direction = 1
    unit = unit or ("تومان" if re.search(r"تومان|تومن|toman", t) else None)
    amount = _rial(amount_raw, unit)
    acct = None
    for a in _ACCT.finditer(raw):
        d = re.sub(r"[^\d]", "", a.group(1))
        if len(d) >= 4 and a.start(1) != m.start(1) and not (bal and bal.start(1) <= a.start(1) < bal.end()):
            acct = d[-4:]
            break
    mm = _MERCH.search(raw)
    merchant = re.sub(r"\s+", " ", mm.group(1)).strip(" ،,.-") or None if mm else None
    dt = _DATE.search(raw)
    return {"bank": name, "type": kind, "direction": direction, "amount": amount, "amount_toman": amount // 10,
            "balance": _rial(bal.group(1), bal.group(2) or unit) if bal else None, "account": acct,
            "merchant": merchant, "date_text": dt.group(1) if dt else None}


# ---------- classification ----------
_DELIVERY = re.compile(r"مرسوله|بسته شما|تحویل|ارسال شد|پیک|سفارش|کد رهگیری|رهگیری|پست|tracking|delivered|shipped|order|courier", re.I)
_AD = re.compile(r"تخفیف|جشنواره|حراج|ویژه|هدیه|قرعه ?کشی|عضویت|ثبت ?نام کنید|کلیک کنید|\d+ ?%|٪|discount|sale|offer|promo|https?://|www\.", re.I)
CLASSES = ("otp", "bank", "delivery", "ad", "personal", "service")


def classify(body, sender, known_contact=False):
    """One of CLASSES plus the extracted data: (cls, otp_code, bank_dict)."""
    t = text(body)
    if LAGHV.search(t):
        return "ad", None, None
    code, b = otp(body), bank(body)
    if code and (STRONG_OTP.search(t) or not b): cls = "otp"
    elif b: cls, code = "bank", None
    elif _DELIVERY.search(t) and not is_mobile(sender): cls = "delivery"
    elif _AD.search(t) and not known_contact: cls = "ad"
    elif known_contact or is_mobile(sender): cls = "personal"
    else: cls = "service"
    return cls, code, b


if __name__ == "__main__":
    assert text("كيك ي‌خواهم ۱۲۳") == "کیک ی خواهم 123"
    assert otp("کد تایید شما ۴۵۶۷۸۹ است") == "456789"
    assert bank("بانک ملی\nبرداشت:1,500,000\nمانده:2,000,000")["amount"] == 1500000
    assert jalali_to_gregorian(1403, 2, 11) == (2024, 4, 30) and jalali_to_gregorian(1403, 1, 1) == (2024, 3, 20)
    print("parse self-check ok")
