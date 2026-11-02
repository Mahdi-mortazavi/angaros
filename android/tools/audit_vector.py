# Independent reference for the audit hash in shared/PROTOCOL.md; its output is pasted into AuditTest.kt.
import hashlib

def entry_hash(prev, fields):
    s = "\u001f".join("" if f is None else str(f) for f in fields)
    return hashlib.sha256((prev + "\n" + s).encode("utf-8")).hexdigest()

e1 = [1, 1767225600000, "c1a2b3", "hermes", "+989000000101", "سلام! کد شما ۱۲۳۴ است، ممنون‌ام", 2, "sent", None]
h1 = entry_hash("0" * 64, e1)
e2 = [2, 1767225660000, "c1a2b3", "hermes", "+989000000101", "سلام! کد شما ۱۲۳۴ است، ممنون‌ام", 2, "delivered", None]
h2 = entry_hash(h1, e2)
print(h1)
print(h2)
