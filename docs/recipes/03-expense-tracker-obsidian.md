# Expense tracker into Obsidian

Goal: every card payment / withdrawal becomes a row in an Obsidian note; a monthly summary is computed on demand.

```yaml
rules:
  - name: expenses
    on: sms
    if: {class: bank}
    do:
      - append: "~/Obsidian/Inbox/Expenses.md"
        line: "| {time} | {bank} | {type} | {amount_toman} | {merchant} |"
```

The note becomes a Markdown table (add the header `| time | bank | type | toman | merchant |` once by hand).

Monthly report with the SDK (`angaros run month.py`):

```python
s = phone.bank(since="30d", summary=True)
print(f"in {s['in_toman']:,}  out {s['out_toman']:,}  toman")
for merchant, a in list(s["by_merchant"].items())[:10]:
    print(f"{a['amount'] // 10:>12,}  {merchant}")
```

Merchant names come from SMS text (untrusted): store them, do not execute or follow them.
