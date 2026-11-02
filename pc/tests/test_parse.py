# python pc/test_parse.py  -- normalisation, OTP, bank parser and classifier on realistic Iranian SMS.
import os, sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from phonebridge import parse as P

fails = []


def eq(name, got, want):
    if got != want:
        fails.append(f"{name}: got {got!r} want {want!r}")


# --- normalisation
eq("arabic yeh/kaf", P.text("كتاب علي"), "کتاب علی")
eq("digits fa/ar/en", P.text("۱۲۳ ١٢٣ 123"), "123 123 123")
eq("zwnj -> space", P.text("می‌خواهم"), "می خواهم")
eq("diacritics+tatweel", P.text("سـلامٌ"), "سلام")
for n in ("+989000000101", "00989000000101", "989000000101", "9000000101", "09000000101", "۰۹۰۰ ۰۰۰ ۰۱۰۱", "+98 900-000-0101"):
    eq(f"number {n}", P.number(n), "09000000101")
eq("short code", P.number("1000-5566"), "10005566")

# --- OTP
OTP = [
    ("کد تایید شما ۴۵۶۷۸۹ است", "456789"),
    ("Your verification code is 4821", "4821"),
    ("<#> 123456 is your Snapp code\nabcDEF123", "123456"),
    ("کد ورود شما به دیوار: 52341", "52341"),
    ("رمز یکبار مصرف: ۷۷۸۸۹۹\nبانک ملت", "778899"),
    ("مبلغ 150000 ریال، کد تایید 5566", "5566"),
    ("رمز دوم پویا: 83920145\nمبلغ: 1,200,000 ریال\nپذیرنده: اسنپ\nزمان اعتبار: 2 دقیقه", "83920145"),
    ("Code 1234. Do not share. Call 02112345678", "1234"),
    ("كد تأييد: ٩٨٧٦", "9876"),
    ("meeting at 1830 tomorrow", None),
    ("سلام، فردا ساعت 1830 جلسه داریم", None),
    ("مرسوله شما با کد رهگیری: 12345678 ارسال شد", None),
    ("کد پستی 1234567890 ثبت شد", None),
    ("ورود به حساب در 1403/07/15 انجام شد", None),
]
for body, want in OTP:
    eq(f"otp {body[:25]!r}", P.otp(body), want)

# --- bank
B = [
    ("بانک ملی ایران\nبرداشت:1,500,000\nاز:0110012345007\nمانده:25,300,000\n1403/07/15-12:30",
     dict(bank="melli", type="withdraw", direction=-1, amount=1500000, balance=25300000, account="5007", date_text="1403/07/15-12:30")),
    ("بانك ملت\nحساب7036123456\nبرداشت500,000ريال\nمانده1,234,567ريال\n0715-12:30",
     dict(bank="mellat", type="withdraw", amount=500000, balance=1234567, account="3456", date_text="0715-12:30")),
    ("بانک سامان\nواریز 2,000,000 ریال\nبه 849-800-1234567-1\nمانده 10,500,000\n1403/07/16 09:15",
     dict(bank="saman", type="deposit", direction=1, amount=2000000, balance=10500000, account="5671")),
    ("بانک پاسارگاد\nخرید از فروشگاه دیجی کالا\nمبلغ: 3,450,000 ریال\nکارت: 5022-29**-****-1234\nمانده: 8,000,000\n1403/08/01-20:11",
     dict(bank="pasargad", type="payment", direction=-1, amount=3450000, account="1234", merchant="فروشگاه دیجی کالا")),
    ("بلو\nواریز ۵۰۰,۰۰۰ تومان\nموجودی: ۲,۳۰۰,۰۰۰ تومان",
     dict(bank="blu", type="deposit", amount=5000000, amount_toman=500000, balance=23000000)),
    ("بانک تجارت\nانتقال:-1,000,000\nاز:7001\nمانده:12,000,000\n0812-14:22",
     dict(bank="tejarat", type="transfer", direction=-1, amount=1000000, account="7001")),
    ("بانک ملی\nانتقال:+3,000,000\nبه:0110012345007\nمانده:40,000,000",
     dict(type="transfer", direction=1, amount=3000000)),
    ("بانک سپه\nبرداشت از خودپرداز\nمبلغ 2,000,000 ریال\nمانده 1,000,000",
     dict(bank="sepah", type="withdraw", amount=2000000)),
    ("بانک کشاورزی\nپرداخت قبض\nمبلغ:450,000\nکارت:...9876\nموجودی:3,000,000",
     dict(bank="keshavarzi", type="payment", amount=450000, account="9876")),
    # precedence: a purchase debited from the account is a payment, an incoming transfer is a deposit
    ("بانک رفاه\nبرداشت بابت خرید\nمبلغ:120,000\nمانده:900,000", dict(bank="refah", type="payment", amount=120000)),
    ("بانک مسکن\nواریز انتقالی\nمبلغ:7,000,000\nمانده:9,000,000", dict(bank="maskan", type="deposit", direction=1)),
]
for body, want in B:
    got = P.bank(body) or {}
    for k, v in want.items():
        eq(f"bank {body[:20]!r}.{k}", got.get(k), v)
for body in ("سلام، فردا جلسه داریم", "جشنواره تخفیف! فقط 99,000 تومان لغو11", "سفارش شما به مبلغ 500,000 ریال ثبت شد",
             "کارت هدیه 500,000 تومانی برنده شدید لغو11"):
    eq(f"not bank {body[:20]!r}", P.bank(body), None)

# --- classification
C = [
    ("سلام داداش، کجایی؟", "09000000101", False, "personal"),
    ("کد تایید شما ۴۵۶۷۸۹ است", "10004321", False, "otp"),
    (B[0][0], "+9899999", False, "bank"),
    ("مرسوله شما با کد رهگیری 123456789012 ارسال شد", "50001234", False, "delivery"),
    ("جشنواره پاییزه دیجی‌کالا تا 70% تخفیف لغو11", "3000123", False, "ad"),
    ("قبض برق شما صادر شد. مهلت پرداخت 1403/08/10", "8888", False, "service"),
    ("hey it's me", "Sara", True, "personal"),
]
for body, sender, known, want in C:
    eq(f"class {body[:20]!r}", P.classify(body, sender, known)[0], want)


# --- held-out corpus (independent verifier): (label, sender, body, expected fields)
HELD_OUT = [  # written by an independent verifier, not by the parser author
 ("mellat-amaliat", "+98700011", "بانک ملت\nعملیات: برداشت\nکارت: 6104-33**-****-0001\nمبلغ: 1,200,000 ریال\nمانده: 8,450,000 ریال\n1403/02/11-09:14",
  dict(cls="bank", bank="mellat", type="withdraw", amount=1200000, balance=8450000, account="0001")),
 ("pasargad-dotted-acct", "+98200020", "پاسارگاد\nواریز به 21.8000.12345678.1\n+5,000,000\n1403/02/11\n13:45\nمانده: 7,800,000",
  dict(cls="bank", bank="pasargad", type="deposit", amount=5000000, balance=7800000, account="6781")),
 ("saman-dashed-acct", "+98200030", "بانک سامان\nانتقال از 849-800-1234567-1\n-1,000,000\nمانده 2,300,000\n1403/02/11",
  dict(cls="bank", bank="saman", type="transfer", direction=-1, amount=1000000, balance=2300000, account="5671")),
 ("saderat-merchant", "+98700050", "بانک صادرات\nخرید\nمبلغ: 450,000 ریال\nکارت: 6037-69**-****-0002\nمانده: 3,100,000\n1403/02/11 18:22",
  dict(cls="bank", bank="saderat", type="payment", amount=450000, balance=3100000, account="0002", merchant=None)),
 ("melli-arabic-yk-persian-digits", "+98700990", "بانك ملي ايران\nبرداشت:۱٬۵۰۰٬۰۰۰\nحساب:۰۱۰۱۲۳۴۵۶۷۰۰۸\nمانده:۲۳٬۴۵۶٬۷۸۹\n۱۴۰۳/۰۲/۱۱\n۱۲:۰۳",
  dict(cls="bank", bank="melli", type="withdraw", amount=1500000, balance=23456789, account="7008")),
 ("blu-toman", "Blu", "بلو\nواریز ۲۵۰٬۰۰۰ تومان به حساب شما\nموجودی: ۱٬۲۵۰٬۰۰۰ تومان",
  dict(cls="bank", bank="blu", type="deposit", amount=2500000, balance=12500000)),
 ("incoming-card2card", "+98700012", "بانک ملت\nکارت به کارت\nانتقال از کارت 5022-29**-****-1234 به کارت شما\nمبلغ: 1,000,000 ریال\n1403/02/11",
  dict(cls="bank", type="transfer", direction=1, amount=1000000)),
 ("refah-with-melli-word", "+98700080", "بانک رفاه کارگران\nبرداشت از حساب 123456789\nمبلغ: 700,000 ریال\nبابت: پرداخت قبض\nمانده: 1,000,000 ریال\nکد ملی مشتری ثبت شد",
  dict(cls="bank", bank="refah", type="withdraw", amount=700000)),
 ("tejarat-kod-shobe", "+98700060", "بانک تجارت\nبرداشت: 2,000,000\nمانده: 5,000,000\nکد شعبه: 1234\n1403/02/11",
  dict(cls="bank", bank="tejarat", type="withdraw", amount=2000000, balance=5000000)),
 ("keshavarzi-compact", "+98700090", "بانک کشاورزی\n+5,000,000\nحساب 123456789\n14030211 1030\nمانده 12,000,000",
  dict(cls="bank", bank="keshavarzi", type="deposit", amount=5000000, balance=12000000, account="6789")),
 ("sepah-mojoodi-ghabel", "+98700070", "بانک سپه\nخرید: 500,000 ریال\nموجودی قابل برداشت: 2,345,000 ریال\n1403/02/11",
  dict(cls="bank", bank="sepah", type="payment", amount=500000, balance=2345000)),
 ("ayandeh-english", "Ayandeh", "Bank Ayandeh\nPurchase: 3,250,000 IRR\nCard: ****4411\nBalance: 9,000,000 IRR\nat SNAPP FOOD\n2024/05/01 20:11",
  dict(cls="bank", type="payment", amount=3250000, balance=9000000, account="4411", merchant="SNAPP FOOD")),
 ("maskan-satna", "+98700100", "بانک مسکن\nحواله ساتنا\nمبلغ: 150,000,000 ریال\nاز حساب: 3300123456\nمانده: 10,000,000 ریال",
  dict(cls="bank", bank="maskan", type="transfer", amount=150000000, balance=10000000)),
 ("mellat-sharh-variz", "+98700012", "بانک ملت\nبرداشت از حساب 1234567890\nمبلغ: 3,000,000 ریال\nشرح: واریز به کارت دیگران\nمانده: 1,000,000 ریال",
  dict(cls="bank", type="withdraw", direction=-1, amount=3000000)),
 # OTPs
 ("otp-google-G-dash", "Google", "G-482913 is your Google verification code.", dict(cls="otp", otp="482913")),
 ("otp-whatsapp-dash", "WhatsApp", "Your WhatsApp code: 123-456\nDon't share this code with others", dict(cls="otp", otp="123456")),
 ("otp-grouped-digits", "PhotoDemo", "123 456 is your PhotoDemo login code, keep it private.", dict(cls="otp", otp="123456")),
 ("otp-dynamic-pin-bank", "+98700012", "رمز دوم پویا\nکارت: 6219-86**-****-1234\nمبلغ: 1,000,000 ریال\nپذیرنده: دیجی کالا\nرمز: 82736451\nاعتبار تا 14:32", dict(cls="otp", otp="82736451")),
 ("otp-date-first", "Snapp", "۱۴۰۳/۰۲/۱۱ کد ورود شما به اسنپ: ۵۵۶۶\n@snapp.ir #5566", dict(cls="otp", otp="5566")),
 ("otp-with-support-tel", "+98300012", "کد تایید شما: 7781\nپشتیبانی: 021-91001234", dict(cls="otp", otp="7781")),
 ("otp-code-after-text", "Divar", "به دیوار خوش آمدید\n1290 کد ورود شماست", dict(cls="otp", otp="1290")),
 ("otp-tracking-and-code", "Tapsi", "سفارش 12345678 ثبت شد. کد تایید تحویل: 4321", dict(cls="otp", otp="4321")),
 ("otp-tooman-amount", "+98700012", "رمز یکبار مصرف 5512 جهت پرداخت 250000 تومان به فروشگاه X", dict(cls="otp", otp="5512")),
 ("otp-year-in-text", "MyIrancell", "کد ورود شما 2024 نیست! کد: 918273", dict(cls="otp", otp="918273")),
 # ads
 ("ad-discount-code", "3000123", "جشنواره بهاره! با کد 1404 تا 30% تخفیف بگیرید\nلغو11", dict(cls="ad", otp=None)),
 ("ad-with-sefaresh", "30001122", "همین حالا سفارش دهید و هدیه بگیرید digikala.com\nلغو11", dict(cls="ad")),
 ("ad-installment-card", "30008888", "خرید اقساطی با کارت بانکی تا سقف 50,000,000 ریال\nلغو 11", dict(cls="ad")),
 ("ad-from-mobile", "09000000101", "فروش ویژه آپارتمان در پردیس، تماس بگیرید\nلغو11", dict(cls="ad")),
 # delivery
 ("delivery-post", "Post", "مرسوله شما با کد رهگیری 123456789012345678901234 تحویل پست شد", dict(cls="delivery", otp=None)),
 ("delivery-digikala", "DIGIKALA", "سفارش 98765432 شما ارسال شد. پیگیری: dgka.la/x", dict(cls="delivery", otp=None)),
 # personal / service
 ("personal-toman-colloquial", "09000000102", "سلام ۵۰۰ تومن کارت به کارت کردم، رسید رو ببین", dict(cls="personal")),
 ("service-operator", "OPDEMO", "مشترک عزیز، طرح اینترنت ماهانه‌ی شما فعال گردید", dict(cls="service")),
]

for label, sender, body, exp in HELD_OUT:
    cls, code, b = P.classify(body, sender)
    got = {"cls": cls, "otp": code, **(b or {})}
    for k, v in exp.items():
        eq(f"held-out {label}.{k}", got.get(k), v)

if fails:
    print("\n".join("FAIL " + f for f in fails))
    print(f"{len(fails)} failures")
    sys.exit(1)
print("test_parse: all checks passed")
