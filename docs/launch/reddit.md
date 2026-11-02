# Reddit drafts

Post each only where self-promotion is allowed (check every subreddit's rules on the day), space them
out, and answer comments. Do not cross-post the same text.

---

## r/selfhosted

**Title:** I built a self-hosted bridge that lets my AI agents read my phone's SMS, with a relay that only stores ciphertext

Angaros syncs an Android phone's SMS, contacts and call log to your computer through a Cloudflare
Worker that **you** deploy (free tier). Phone and computer share an AES-256-GCM key; the Worker is a
~100-line mailbox for encrypted blobs with a 7-day TTL. No inbound ports on either side, no account,
no third-party server.

On the computer you get a local SQLite with full-text search, a JSON CLI, a Python SDK, a REST/SSE
API, an MCP server and a YAML rules engine (cron, webhooks, append to notes, LLM digests).

Honest status: verified on one phone so far (Samsung A30s, Android 16), SMS only, Android only.
MIT: https://github.com/Mahdi-mortazavi/angaros. Device reports very welcome.

---

## r/ClaudeAI

**Title:** Gave Claude Code my phone: an MCP server for SMS, OTP codes, contacts and call log (open source)

`claude mcp add phone --scope user -- angaros-mcp` and Claude can search my texts, wait for a
login code (`otp_wait`), summarise the day and, after a dry-run preview, send an SMS. Every send is
logged with the agent's name in a hash-chained audit log on the phone and the computer.

Because anyone can text you, message text is treated as untrusted (prompt injection): results are
flagged, and the server instructions tell the model never to follow instructions inside messages.

Repo and setup (about a minute once the relay is deployed): https://github.com/Mahdi-mortazavi/angaros

---

## r/mcp

**Title:** Angaros: MCP server exposing an Android phone (22 tools, output schemas, destructive hints, idempotent sends)

Design notes that may interest this sub:

- every tool has an output schema; errors come back as `isError` with `{error, code}`;
- `send_sms`, `rules_add/remove/run` are `destructiveHint: true`; `send_sms` takes `dry_run` and a
  per-agent `idempotency_key` (the same key within 24 h returns the first result with `already_sent`);
- cursor paging everywhere, an `events_since` feed, 3 resources and 4 prompts;
- stdio, or streamable HTTP on 127.0.0.1 with a bearer token.

Python package `angaros` (`angaros-mcp`), MIT: https://github.com/Mahdi-mortazavi/angaros

---

## r/androidapps

**Title:** [Dev] Angaros: open-source app that connects your SMS to AI assistants on your PC, end-to-end encrypted

Material 3 Expressive app (English and Persian) that syncs your SMS, contacts and call log to your
own computer through an encrypted relay you host. It shows connection health, a classified message
feed with one-tap OTP copy, every SMS your agents sent (with a tamper-evident record) and your
automations. No ads, no analytics, no account.

Not on Play (Google restricts SMS permissions to default SMS apps), so it is a signed APK on GitHub,
Obtainium-friendly: https://github.com/Mahdi-mortazavi/angaros/releases
Tested on one phone so far; I'd be grateful for reports from other devices.
