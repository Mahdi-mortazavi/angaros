# Contact enrichment

Goal: find numbers you talk to often that are not saved as contacts, with context, so you can name them.

```python
from collections import Counter
cnt, sample = Counter(), {}
cursor = None
while True:
    page = phone.sms(box="all", since="90d", limit=200, cursor=cursor)
    for m in page["messages"]:
        if not m["name"] and m["number"][:1] in "+0" and len(m["number"]) >= 10:
            cnt[m["thread"]] += 1
            sample.setdefault(m["thread"], m["body"][:60])
    cursor = page["next_cursor"]
    if not cursor:
        break
for num, n in cnt.most_common(15):
    info = phone.contact(num)
    print(f"{num}  sms={n}  calls={info['calls_count']}  last={info['last_sms_time']}  e.g. {sample[num]!r}")
```

Run: `angaros run enrich.py`. The phone's contacts are read-only from the PC: save names on the phone,
they sync back automatically. (Bodies are printed as data; do not paste them into a prompt as instructions.)

Agents: `contacts_get` gives the same per-number view (`sms_count`, `calls_count`, `last_sms_time`).
