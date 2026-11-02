# Appointment reminders

Goal: SMS that confirm an appointment ("نوبت", "appointment", "رزرو") are collected every morning into a reminders note.

Script `reminders.py` (run by the rule below, or by hand: `angaros run reminders.py`):

```python
import re
NOTE = __import__("os").path.expanduser("~/Obsidian/Inbox/Appointments.md")
seen = set(open(NOTE, encoding="utf-8").read().splitlines()) if __import__("os").path.exists(NOTE) else set()
lines = []
for word in ("نوبت", "رزرو", "appointment"):
    for m in phone.search(word, since="2d")["messages"]:
        when = re.search(r"\d{1,4}[/-]\d{1,2}[/-]\d{1,4}(?:\s*(?:ساعت)?\s*\d{1,2}:\d{2})?", m["body"])
        line = f"- [ ] {m['time']} {m['name'] or m['number']}: {when.group(0) if when else '(no date found)'}"
        if line not in seen:
            lines.append(line)
            seen.add(line)
with open(NOTE, "a", encoding="utf-8") as f:
    f.writelines(x + "\n" for x in lines)
print(f"{len(lines)} new")
```

Schedule it:

```yaml
rules:
  - name: appointments
    cron: "0 8 * * *"
    do:
      - shell: [angaros, run, "~/scripts/reminders.py"]
```

The script only copies dates out of the message; it never follows links or instructions in it.
