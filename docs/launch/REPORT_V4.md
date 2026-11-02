# گزارش V4: بسته‌ی متن‌باز Angaros

- **تاریخ:** 2026-11-02
- **خروجی:** ریشه‌ی همین مخزن (تاریخچه‌ی git ندارد؛ `git init -b main` اجرا و همه‌ی فایل‌ها stage شده‌اند، ولی **هیچ commitی ساخته نشده**، remote اضافه نشده و چیزی push یا منتشر نشده است).
- **مبنا:** سورس مخزن خصوصی قبلی (`phone-bridge-app`)، نسخه‌ی 3.0.3 (versionCode 30003). مشخصات: `SPEC_V4_OSS.md` به‌همراه `SPEC_V4_ADDENDUM.md` (که بر آن مقدم است).
- **اجرای هم‌زمان:** پیش از شروع بررسی شد. تنها فرایند `claude -p` همین اجرا بود. بقیه‌ی `claude.exe`ها مربوط به اپ دسکتاپ بودند. یک اجرای V4 قبلی پوشه‌ی نیمه‌کاره‌ی `phonebridge-oss` را باقی گذاشته بود؛ به آن دست نزدم و از آن هم استفاده نکردم.

## ۱. چه چیزی در درخت هست

| بخش | محتوا |
|---|---|
| `android/` | سورس برنامه (applicationId همان `app.phonebridge` است). امضای release از `keystore.properties` یا متغیرهای `SIGNING_*` خوانده می‌شود. **اگر کلیدی نباشد، APK بدون امضا ساخته می‌شود، نه با کلید debug.** Robolectric mirror به یک property اختیاری تبدیل شد. `ScreenshotTest.kt` (جدید) با داده‌ی ساختگی تصویر می‌سازد. |
| `worker/` | رله، migration، `wrangler.toml` با شناسه‌ی D1 صفر (placeholder)، `package.json`، **تست‌های جدید `node --test`** (۹ تست با D1 درون‌حافظه‌ای) و `test/e2e.py`. |
| `pc/` | پکیج PyPI با نام `angaros` (نام import همان `phonebridge` است). کنسول‌اسکریپت‌ها `angaros` و `angaros-mcp` هستند و دستور جدید `angaros mcp` هم اضافه شد. shimهای `pc/phone.py` و `pc/phone_mcp.py` حفظ شدند. پوشه‌ی داده `~/.angaros` است (یا `ANGAROS_HOME`، یا `PHONE_BRIDGE_DIR` قدیمی). تست‌ها در `pc/tests/` هستند و `run_all.py` همه را اجرا می‌کند. |
| `shared/` | پروتکل و بردارهای تست. هر دو بردار از داده‌ی ساختگی دوباره ساخته شدند. |
| `docs/` | `index.html` (سایت Pages، روشن و تیره، دکمه‌ی کپی، تگ‌های OG)، `AGENTS.md`، `performance.md`، `self-hosting.md`، `troubleshooting.md`، ۱۰ دستورالعمل، `assets/` (social preview با ابعاد 1280×640، اسکرین‌شات‌ها، لوگو) و `launch/`. |
| `skills/angaros/` | Skill ایجنت با نام و دستورهای عمومی. |
| `examples/` | `today.py` (اسکریپت SDK که در تست اجرا می‌شود). |
| `scripts/privacy_scan.py` | اسکنر راز و داده‌ی شخصی. در CI اجرا می‌شود. |
| ریشه | `README.md`، `README.fa.md`، `LICENSE` (MIT)، `SECURITY.md`، `CONTRIBUTING.md`، `CODE_OF_CONDUCT.md`، `SUPPORT.md`، `CHANGELOG.md`، `AGENTS.md`، `llms.txt`، `server.json`، `ruff.toml`، `.editorconfig`، `.gitattributes`، `.gitignore`، پیکربندی release-please |
| `.github/` | `ci.yml` (اندروید، worker، پایتون 3.10 تا 3.13، ruff، اسکن حریم خصوصی، CodeQL، dependency review)، `release.yml`، `release-please.yml`، `pages.yml`، سه فرم issue، قالب PR، CODEOWNERS، dependabot، FUNDING (کامنت‌شده) و `release.yml` برای دسته‌بندی یادداشت‌های انتشار |

## ۲. با شاهد تأیید شد

| بررسی | نتیجه |
|---|---|
| تست‌های پایتون (`pc/tests/run_all.py`، venv پایتون 3.13، از روی همین درخت) | **9/9 PASS** (parse، phone، mcp، v3، v3_fixes، docs، doctor، retry و rules check) |
| ruff (`pc worker/test android/tools examples`) | **All checks passed** |
| تست‌های worker (`npm test`) | **9/9 pass**. با دو mutant اثبات شد که تست‌ها واقعاً شکست می‌خورند: rate limit و اعتبارسنجی blob هر دو قرمز شدند و بعد سورس برگردانده شد. |
| گیت اندروید (`clean lintDebug testDebugUnitTest assembleDebug`) | **BUILD SUCCESSFUL، EXIT=0**: ۴۳ تست (۳۶ اجرا، ۷ تست اسکرین‌شات طبق طراحی skip شدند چون opt-in هستند)، ۰ شکست و ۰ خطا. lint با warningsAsErrors صفر issue داشت. |
| سازگاری بردارها بین Kotlin و پایتون پس از ساخت دوباره با داده‌ی ساختگی | `CryptoTest`، `ParseTest` و `LogicTest` سبز شدند. هش زنجیره‌ی audit به‌طور مستقل با `hashlib` هم بررسی شد. |
| ساخت release با کلید **دورریختنی** (`CN=Angaros throwaway CI test`، بیرون از درخت) | `assembleRelease` با R8 موفق بود. هر سه APK (`universal`، `arm64-v8a`، `armeabi-v7a`) نتیجه‌ی `apksigner verify` برابر 0 داشتند، versionCode=30003، versionName=3.0.3 و label=Angaros. |
| ساخت release **بدون کلید** | APKها با پسوند `-unsigned` ساخته شدند و `apksigner verify` برای آن‌ها 1 برگرداند، یعنی کلید debug هرگز استفاده نمی‌شود. |
| کد native | تنها کتابخانه‌ی native `libandroidx.graphics.path.so` است. APKهای ABI حدود ۵۰ کیلوبایت کوچک‌ترند و این صادقانه در README آمده است. |
| پکیج پایتون (`python -m build pc`) | `angaros-3.0.3-py3-none-any.whl` و sdist ساخته شدند. metadata شامل `License-Expression: MIT` و `Requires-Python >=3.10` است و `openapi.yaml` داخل wheel هست. |
| نام PyPI | `https://pypi.org/pypi/angaros/json` پاسخ **404** داد، پس نام آزاد است (2026-11-02). |
| actionlint 1.7.12 روی هر ۴ workflow | **rc=0**. shellcheck نصب نبود، پس اسکریپت‌های شل داخل workflowها shellcheck نشدند. |
| اسنیپت‌های مستندات | `test_docs` همه‌ی YAMLهای rules در recipes، AGENTS، SKILL و **README** را با `rules check` بررسی می‌کند و recipe 03، recipe 09 و `examples/today.py` را واقعاً اجرا می‌کند. با mutation اثبات شد که اسنیپت README بررسی می‌شود. |
| اسکرین‌شات‌ها | ۷ تصویر از صفحه‌های واقعی برنامه با داده‌ی ساختگی (Robolectric، حالت native graphics). همه را دیدم: فقط نام‌ها، شماره‌ها و متن‌های ساختگی در آن‌ها هست. |
| Social preview و سایت | با Edge headless رندر و بررسی چشمی شدند. یک باگ کشیدگی تصویر در سایت پیدا و رفع شد. |

## ۳. نتیجه‌ی اسکن راز و داده‌ی شخصی (اسکن خودم)

**لایه‌ی ۱: `scripts/privacy_scan.py`** (در درخت هست و در CI اجرا می‌شود). موارد بررسی‌شده:
- کلید خصوصی و توکن‌های GitHub، AWS، OpenAI، Anthropic، Slack و Cloudflare
- رشته‌ی `pb1:` و انتساب رازها (`PAIR_MASTER`، رمز keystore، `audit_key`)
- شماره‌ی موبایل ایران به همه‌ی شکل‌ها (`09…`، `+98`، `0098`، ارقام فارسی)، کارت ۱۶ رقمی و IBAN
- ایمیل غیر از ایمیل عمومی تماس، و URLهای `workers.dev` غیر از placeholderها
- زیردامنه‌ی Worker مالک، شناسه‌های حساب Cloudflare، شناسه‌ی D1 و مسیرهای محلی کاربر

نتیجه:
- **اجرای اول ۱۴ یافته داشت.** موارد واقعی این‌ها بودند: **مسیرهای شخصی Obsidian مالک** (پوشه‌ی کاربر در OneDrive) در ۵ recipe و در `rules.example.yaml`، و یک مسیر در پوشه‌ی داده‌ی Hermes. همه با مسیرهای `~/` عوض شدند و `append` حالا `~` را باز می‌کند. بقیه‌ی یافته‌ها مثبت کاذب بودند (برچسب Kotlin، الگوی `@domain #code`، بردار تست با URL نمونه، و رمز تست) و اسکنر برایشان اصلاح شد.
- **اجرای نهایی: `privacy_scan: 0 finding(s)`.**
- **تست کاشتنی:** فایلی با ۱۱ نوع راز یا داده‌ی شخصی ساختم. همه گرفته شدند (قاعده‌ی کارت ابتدا ضعیف بود و سخت‌گیرتر شد). خط تمیز با شماره‌های ساختگی هیچ یافته‌ای نداشت.

**لایه‌ی ۲: مقایسه با داده‌ی واقعی مالک** (`_angaros_tools/deep_scan.py`، بیرون از درخت). `pc/phone.db` را فقط‌خواندنی (`mode=ro`) باز کردم و درخت را با این‌ها مقایسه کردم: ۹۷۰ شماره‌ی واقعی، ۲۷۹ نام مخاطب، و ۸۷٬۱۵۳ تکه‌ی ۲۸ حرفی از متن پیامک‌ها.
- **اجرای اول:** یک کد کوتاه بانکی عمومی، یک متن قالبی کد اینستاگرام و یک متن قالبی اپراتور («مشترک گرامی…») در فیکسچرهای parser با پیامک‌های واقعی هم‌پوشانی داشتند. این‌ها داده‌ی شخصی نیستند (پیام انبوه هستند)، ولی بازنویسی شدند و بردارها دوباره ساخته شدند.
- **اجرای نهایی:** **۰ شماره، ۰ نام مخاطب.** تنها هم‌پوشانی‌های متنی باقی‌مانده URL عمومی GitHub مالک (`github.com/Mahdi-mortazavi`) و ایمیل عمومی تماس هستند که در پیامک‌های خود مالک هم آمده‌اند.
- **تصویرها:** متادیتای ۱۷ فایل PNG خالی است. هیچ‌یک از اسکرین‌شات‌ها یا dumpهای `qa/` و `dist/screenshots` کپی نشدند.
- **فایل‌های ممنوع** در درخت نیستند: config.json، phone.db، pairing.png، api_token، audit.head، `.dev.vars`، لاگ‌ها، `claude_*`، `local.properties`، keystore، REPORT*/SPEC* و APK. در `pc/` قبلی یک فایل `api_token` بود که کپی نشد.

## ۴. پیاده‌شده ولی تأییدنشده (UNVERIFIED)

- **هیچ workflowی روی GitHub اجرا نشده است**، چون push ممنوع بود. نسخه‌ی actionها از حافظه انتخاب شده‌اند (checkout@v5، setup-java@v5، setup-python@v6، setup-node@v5، gradle setup@v4، codeql@v3، attest-build-provenance@v2 و غیره)؛ Dependabot آن‌ها را به‌روز می‌کند. CodeQL برای Kotlin حالت `manual` با `compileDebugKotlin` دارد که اجرا نشده است. اولین اجرای CI ممکن است یک دور اصلاح لازم داشته باشد.
- **release-please:** پیکربندی (`simple`، extra-files برای Gradle، pyproject، package.json و server.json) آزموده نشده است.
- **`server.json`:** با `mcp-publisher validate` بررسی نشده و آدرس schema (2025-09-29) از حافظه است.
- **تست‌های پایتون فقط روی 3.13 (ویندوز) اجرا شدند،** نه روی 3.10، 3.11 و 3.12. ruff یک خطای نحوی واقعی مخصوص پایتون 3.10/3.11 پیدا کرد (f-string در `test_phone.py`) که رفع شد.
- **`worker/test/e2e.py`** (Worker واقعی زیر `wrangler dev`) در این اجرا اجرا نشد. فقط مسیرها و نام دیتابیس آن به‌روز شدند.
- **دستورهای Quick start روی یک حساب تازه‌ی Cloudflare اجرا نشده‌اند**، چون دست زدن به Cloudflare ممنوع بود.
- **APKهای ساخته‌شده روی گوشی نصب نشدند**، چون طبق مشخصات به گوشی دست نزدم.
- **عددهای کارایی 3.0.3:** از فایل‌های خام `qa/perf-raw-after{1,2}-3.0.3-speedprofile.txt` محاسبه شدند، چون اجرای perf آن‌ها گزارش جمع‌بندی ننوشته بود. وضعیت verify برای 3.0.3 اندازه‌گیری نشده است. اندازه‌گیری idle سی‌دقیقه‌ای نامعتبر بود (پروسه وسط کار عوض شد) و ادعای باتری نشده است.
- **یک بار شکست گذرا در `test_v3.py`** از ۵ اجرای کامل دیده شد که دلیلش ثبت نشد. اجرای جداگانه ۲ از ۲ و چهار اجرای کامل بعدی سبز بودند. `run_all.py` حالا همه‌ی خط‌های FAIL را چاپ می‌کند تا اگر تکرار شد، علتش معلوم باشد.

## ۵. تصمیم‌هایی که بدون پرسیدن گرفته شد

1. **پیکربندی امضا:** `keystore.properties` یا متغیرهای `SIGNING_*`. بدون کلید، خروجی unsigned است و از کلید debug استفاده نمی‌شود. workflow انتشار اگر secret نباشد یا گواهی `CN=Android Debug` باشد، شکست می‌خورد.
2. **ساختار پکیج:** ماژول‌ها به `pc/phonebridge/` منتقل شدند (`phone_mcp.py` به `mcp_server.py` تغییر نام داد) و importها نسبی شدند. `openapi.yaml` داخل پکیج رفت. `requirements.txt` حذف شد چون `pyproject.toml` جایگزینش است. پوشه‌ی داده‌ی پیش‌فرض `~/.angaros` است، **ولی** shim‌ها اگر `config.json` کنار خودشان باشد همان پوشه را نگه می‌دارند. پس نصب فعلی مالک (`pc/config.json`) بدون تغییر کار می‌کند.
3. **نام MCP server** برای سازگاری با تنظیمات موجود ایجنت‌ها همان `phone` ماند (`mcp__phone__*`).
4. **Worker در `wrangler.toml` نام `angaros` گرفت.** Worker فعلی مالک (`phone-bridge`) دست‌نخورده می‌ماند.
5. **شماره‌های ساختگی** در بازه‌ی `0900 000 01xx` و `+1 555 01xx` هستند (طبق addendum). شماره‌ی ثابت قدیمی فیکسچرها (`0912…`) هم جایگزین شد.
6. **README به عددهای اندازه‌گیری‌شده‌ی 3.0.3 استناد می‌کند** و صریحاً می‌گوید فقط روی یک دستگاه آزموده شده است. جدول مقایسه با رقبا برای موارد نامطمئن «varies» گذاشته است.
7. **دکمه‌ی Deploy to Cloudflare گذاشته نشد**، چون بدون حساب Cloudflare قابل آزمایش نبود. مسیر سه‌دستوری CLI در README آمده است.
8. **CODE_OF_CONDUCT** به Contributor Covenant 2.1 لینک می‌دهد و متن کامل آن کپی نشد.

## ۶. کارهایی که مالک باید انجام یا تأیید کند

فهرست کامل و مرحله‌به‌مرحله در [`PUBLISH_CHECKLIST.md`](PUBLISH_CHECKLIST.md) است. مهم‌ترین‌ها:

1. ساختن commit اول. ساختن repo `Mahdi-mortazavi/angaros` و push.
2. **ساختن کلید امضای release و چهار secret آن.** نصب فعلی گوشی با کلید debug امضا شده، پس برای نصب اولین نسخه‌ی رسمی باید uninstall کرد و دوباره pair کرد.
3. فعال کردن Pages (منبع: Actions)، Discussions، گزارش خصوصی آسیب‌پذیری و اجازه‌ی ساخت PR برای Actions.
4. trusted publisher در PyPI، سپس `PUBLISH_PYPI=true`. بعد از آن `mcp-publisher validate/publish`.
5. آپلود social preview و تنظیم topics از `TOPICS.md`.
6. بازبینی پیش‌نویس‌های لانچ پیش از انتشار.

## ۷. پاک‌سازی

- پوشه‌ی `_angaros_tools` (کنار مخزن، بیرون از درخت) اسکریپت‌های کمکی، venv، کلید دورریختنی و لاگ‌های من را دارد. می‌شود پاکش کرد.
- پوشه‌ی `d` در ریشه‌ی همان درایو هم بقایای یکی از اشتباه‌های مسیر خودم است (یک venv نیمه‌کاره) و می‌شود پاکش کرد. اجازه‌ی حذف خودکارش را نداشتم.
- هیچ subagent یا فرایند پس‌زمینه‌ای در حال اجرا نمانده است.
