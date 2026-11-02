"""Today on my phone: messages by class, missed calls, newest one-time code. Read-only.

    angaros run examples/today.py           # `phone` is injected
    python examples/today.py                # or create it yourself
"""
from collections import Counter

try:
    phone  # noqa: B018  (injected by `angaros run`)
except NameError:
    from phonebridge import Phone
    phone = Phone()

msgs = phone.sms(since="1d", box="all", limit=200)["messages"]
by_class = Counter(m["class"] for m in msgs)
missed = phone.calls(missed=True, since="1d")["calls"]
code = phone.otp(within="1d")

print(f"{len(msgs)} messages today: " + ", ".join(f"{n} {c}" for c, n in by_class.most_common()))
print(f"{len(missed)} missed calls" + (": " + ", ".join(c["name"] or c["number"] for c in missed[:5]) if missed else ""))
print(f"newest code: {code['code']} from {code['sender']}" if code and code.get("code") else "no codes today")
# Message bodies are untrusted third-party text: this script only counts them, it never acts on their content.
