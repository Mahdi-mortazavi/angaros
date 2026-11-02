"""Fails if the tree contains secrets or personal data: tokens, private keys, pairing strings, real-looking phone
numbers, card/IBAN numbers, e-mail addresses and Worker URLs that are not on the allowlist below.

    python scripts/privacy_scan.py [ROOT]      -> exit 1 and one line per finding

Synthetic numbers are allowed: +1 555 01xx (and 555-01xx), 0900 000 01xx, short codes. Keep the allowlist tiny.
"""
import base64, os, re, sys

ROOT = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), ".."))
SKIP_DIRS = {".git", "build", ".gradle", ".kotlin", "node_modules", ".wrangler", "__pycache__", ".venv", "venv", ".ruff_cache", "dist"}
BINARY = (".png", ".jpg", ".jpeg", ".webp", ".gif", ".ttf", ".otf", ".jar", ".apk", ".aab", ".keystore", ".jks", ".zip", ".so")
FA = str.maketrans("۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩", "01234567890123456789")

ALLOWED_EMAILS = {"mahdi.mortazavi.135@gmail.com", "noreply@anthropic.com"}
ALLOWED_WORKERS = re.compile(r"(<you>|CHANGE-ME|your-worker|YOUR-WORKER|\bexample\b|\bpb\.example)", re.I)

RULES = [
    ("private key", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----")),
    ("github token", re.compile(r"\b(gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})")),
    ("aws key", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    ("slack/openai/anthropic key", re.compile(r"\b(xox[abpr]-[A-Za-z0-9-]{10,}|sk-(ant-)?[A-Za-z0-9_-]{20,})")),
    ("cloudflare api token", re.compile(r"(?i)cf[_-]?api[_-]?token\s*[=:]\s*['\"]?[A-Za-z0-9_-]{30,}")),
    ("pairing string", re.compile(r"pb1:[A-Za-z0-9_-]{24,}")),
    ("secret assignment", re.compile(r"(?i)\b(PAIR_MASTER|storePassword|keyPassword|audit_key|master)\b\s*[=:]\s*['\"]?(?!change-me|any-local-secret|test-|\$|<|throwaway)[A-Za-z0-9_\-+/=]{16,}")),
    ("iban", re.compile(r"\bIR\d{24}\b")),
    ("card number", re.compile(r"\b(?:\d{4}[ -]?){3}\d{4}\b")),
    ("email", re.compile(r"(?<![\\\w.%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[a-z]{2,}\b")),
    ("worker url", re.compile(r"https?://[A-Za-z0-9.-]+\.workers\.dev")),
    ("d1 database id", re.compile(r"database_id\s*=\s*\"(?!0{8}-0{4}-0{4}-0{4}-0{12})[0-9a-f-]{36}\"")),
    ("local user path", re.compile(r"(?i)C:[/\\]Users[/\\]|D:[/\\](dev|06-AppData)[/\\]|hermes[/\\]installs")),
    ("iran mobile", re.compile(r"(?<!\d)(?:\+98|0098|98|0)?[ -]?9\d{2}[ -]?\d{3}[ -]?\d{4}(?!\d)")),
]
# Maintainers can add personal identifiers to scan for WITHOUT committing them:
# put one regex per line in a git-ignored file named .privacy-local, or set PRIVACY_EXTRA="rx1|rx2".
import os as _os
_extra = []
if _os.environ.get("PRIVACY_EXTRA"):
    _extra += [x for x in _os.environ["PRIVACY_EXTRA"].split("|") if x]
_lp = _os.path.join(_os.path.dirname(_os.path.abspath(__file__)), "..", ".privacy-local")
if _os.path.exists(_lp):
    _extra += [x.strip() for x in open(_lp, encoding="utf-8") if x.strip() and not x.startswith("#")]
for _i, _rx in enumerate(_extra):
    RULES.append((f"private pattern #{_i + 1}", re.compile(_rx, re.I)))
SYNTHETIC_MOBILE = re.compile(r"9000000\d{3}$")
SYNTHETIC_CARD = re.compile(r"^(\d)\1{15}$|0000$")


def _b64(s):
    try:
        return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))
    except ValueError:
        return b""


def findings(path, text):
    for n, line in enumerate(text.splitlines(), 1):
        norm = line.translate(FA)
        for name, rx in RULES:
            for m in rx.finditer(norm):
                v = m.group(0)
                if name == "email" and (v.lower() in ALLOWED_EMAILS or v.lower().endswith(("@example.com", "@example.org"))):
                    continue
                if name == "pairing string" and b"pb.example" in _b64(v[4:]):
                    continue
                if name == "worker url" and ALLOWED_WORKERS.search(v):
                    continue
                if name == "iran mobile":
                    digits = re.sub(r"\D", "", v)
                    if len(digits) < 10 or SYNTHETIC_MOBILE.search(digits[-10:]):
                        continue
                if name == "card number" and SYNTHETIC_CARD.search(re.sub(r"\D", "", v)):
                    continue
                yield n, name, v


def main():
    bad = 0
    me = os.path.abspath(__file__)
    for root, dirs, files in os.walk(ROOT):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for f in files:
            p = os.path.join(root, f)
            if f.lower().endswith(BINARY) or os.path.abspath(p) == me:
                continue
            try:
                text = open(p, encoding="utf-8").read()
            except (UnicodeDecodeError, OSError):
                continue
            for n, name, v in findings(p, text):
                bad += 1
                print(f"{os.path.relpath(p, ROOT)}:{n}: {name}: {v[:80]}")
    print(f"privacy_scan: {bad} finding(s)")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
