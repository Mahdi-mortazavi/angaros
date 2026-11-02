# Bank alert to Telegram

Goal: a Telegram message for every deposit above 1,000,000 Toman (amounts in rules are Rial: 10,000,000).

Option A, through Hermes' configured Telegram gateway (`hermes send`):

```yaml
rules:
  - name: deposit-alert
    on: sms
    if: {class: bank, bank_type: deposit, amount_gt: 10000000}
    do:
      - notify: telegram
        message: "💰 واریز {amount_toman} تومان به {bank} — مانده {balance} ریال"
```

Option B, straight to the Telegram Bot API (no agent involved). Put your bot token and chat id in the URL / body:

```yaml
rules:
  - name: deposit-alert-bot
    on: sms
    if: {class: bank, bank_type: deposit, amount_gt: 10000000}
    do:
      - webhook: "https://api.telegram.org/botYOUR_BOT_TOKEN/sendMessage"
        json: {chat_id: "YOUR_CHAT_ID", text: "واریز {amount_toman} تومان - {bank} - مانده {balance} ریال"}
```

Fields available for bank SMS: `bank`, `type` (deposit/withdraw/transfer/payment), `amount`, `amount_toman`, `balance`,
`account`, `merchant`, `time`, `number`. Test it without sending anything:
`angaros rules test ~/.angaros/rules.yaml --event '{"event":"sms","class":"bank","type":"deposit","amount":20000000,"amount_toman":2000000,"bank":"mellat"}'`.
