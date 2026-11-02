# Delivery tracking

Two kinds of "delivery":

1. Parcel/courier SMS (class `delivery`) → a tracking log.
2. Delivery reports of SMS *you* sent → an alert when one fails.

```yaml
rules:
  - name: parcels
    on: sms
    if: {class: delivery}
    do:
      - append: "~/Obsidian/Inbox/Parcels.md"
        line: "- {time} {name|number}: {body}"

  - name: sms-delivery-failed
    on_event: [send, delivery]
    if: {status: delivery_failed}
    do:
      - notify: telegram
        message: "پیامک {id} تحویل نشد"

  - name: sms-send-failed
    on_event: send
    if: {status: failed}
    do:
      - notify: telegram
        message: "ارسال پیامک {id} ناموفق بود: {error}"
```

Event statuses: `send` events carry `sent | failed | expired`; `delivery` events carry `delivered | delivery_failed`.
The same stream is available live as SSE (`GET /v1/events`) and as `events_since(cursor)` in MCP/SDK.
