# Nightly SMS digest

Goal: at 22:00 every night, a Persian digest of the day's SMS (personal + services, no OTP) in Obsidian.

```yaml
rules:
  - name: nightly-digest
    cron: "0 22 * * *"
    collect: {since: 24h}
    do:
      - llm: >-
          Write a short Persian digest of today's SMS for me: personal messages first, then bills/banks, then
          services; skip OTP codes and ads. Append it under today's date to
          "~/Obsidian/Inbox/SMS digest.md".
```

Variant without an LLM — a plain list:

```yaml
rules:
  - name: nightly-list
    cron: "5 22 * * *"
    collect: {since: 24h, class: personal}
    do:
      - shell: [python, "~/scripts/digest_to_note.py"]   # gets {"event":"schedule","messages":[...]} on stdin
```

`cron` uses local time; missed minutes (PC asleep at 22:00) are caught up within 24 h on the next daemon tick.
Check the rule: `angaros rules check ~/.angaros/rules.yaml`, dry run: MCP `rules_test {"name": "nightly-digest"}`.
