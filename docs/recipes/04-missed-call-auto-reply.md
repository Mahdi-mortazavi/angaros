# Missed-call auto reply

Goal: when a saved contact calls and you miss it, they get one SMS — at most once per hour per number.

```yaml
rules:
  - name: missed-call-reply
    on: call
    if: {call_type: MISSED, unknown_sender: false}
    cooldown: 1h
    do:
      - reply: "سلام، الان نمی‌تونم جواب بدم. بعداً تماس می‌گیرم."
```

`reply` REALLY sends from your SIM (audited as `agent = rule:missed-call-reply`). Check the audit with
`angaros audit 10`. Replies are only allowed for `sms` / `call` events, so a rule on `send` or `delivery`
events can never create a reply loop. Calls older than `max_event_age` (default 6h, e.g. phone was offline) never
trigger a reply.
