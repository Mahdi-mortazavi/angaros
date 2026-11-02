# Angaros wire protocol (v2 + v3 additions; formerly "Phone Bridge")

## Pairing string
`pb1:` + base64url(JSON `{"u": workerUrl, "t": token, "k": base64(32-byte key)}`), no padding.
The same string is encoded in `pairing.png`.

- `token` = `deviceId + "." + hex(HMAC-SHA256(PAIR_MASTER, deviceId))`. The Worker recomputes it;
  it stores no per-device state. `PAIR_MASTER` lives in the PC `config.json` and as a Worker secret.
- `key` never leaves the phone and the PC.
- The app may override `u` with a custom relay URL (Settings). Token and key stay the same.

## Encryption
AES-256-GCM, 12-byte random nonce, 128-bit tag.
`blob = base64(nonce || ciphertext || tag)`, AAD = UTF-8 `deviceId + "|" + box` where box is `up` or `down`
(a blob cannot be replayed into the other direction or another device).
`test-vector.json` is checked by both `pc/test_phone.py` and the Android unit test.

## Worker (`Authorization: Bearer <token>`)
| call | body / query | reply |
|---|---|---|
| `GET /v1/health` | - (no auth) | `{"ok":true}` |
| `POST /v1/up`, `POST /v1/down` | `{"blob": "..."}` (max 256 KB) | `{"id": n}` |
| `GET /v1/up`, `GET /v1/down` | `?after=cursor&wait=0..25&limit=1..200` | `{"items":[{"id","blob"}], "cursor": n}` |

Items expire after 7 days. 120 requests/min per device, 30 failed auths/min per IP -> 429
(clients back off exponentially on 429/5xx/network errors).

## Plaintext messages
Phone -> PC (`up`):
- `{"t":"sms","items":[{"pid","box":"inbox|sent","number","body","date","date_sent","read","thread"?,"sim"?}]}`
  (`pid` 0 = pushed by the SMS receiver before the provider row existed). The PC dedupes on
  `(box, normalised number, date_sent||date, sha256(body))`, so re-uploads and receiver+provider
  copies of one SMS collapse into one row.
- `{"t":"contacts","full":bool,"items":[{"cid","name","numbers":[..]}],"deleted":[cid..]}`
- `{"t":"calls","items":[{"pid","number","name","type","date","duration"}]}`
  `type` is one of `INCOMING OUTGOING MISSED VOICEMAIL REJECTED BLOCKED` (or the raw int as a string).
- `{"t":"result","id","op","ok","error"?,"status"?,"audit"?, ...}` answer to a command.
  For `send_sms`: `status` = `sent` (radio accepted every part) or `failed`; `audit` = the phone's
  audit entry (see below).
- `{"t":"delivery","id","ok","status":"delivered|failed","ts"}` later delivery report for a send.
- `{"t":"status","ts","paused","version","queue":n,"battery_unrestricted":bool,
   "sims":[{"slot":1,"name":"..."}],
   "backfill":{"sms":[done,total],"calls":[done,total],"contacts":[done,total],"complete":bool}}`
  sent at service start, after every backfill chunk, on `ping`, and when its content changes (5-min heartbeat).
  A kind whose permission is denied reports `[0,0]` and counts as complete.

PC -> phone (`down`): `{"t":"cmd","id","ts","op", ...}`
- `send_sms {"number","text","agent","sim"?:1|2}` — multipart for long text; SENT and DELIVERY
  intents produce `result` and `delivery`.
- `place_call {"number"}` — refused unless enabled in the app (off by default).
- `resync` — full re-upload of SMS, contacts and calls.
- `ping` — replies `result` with `paused`, `version` and the status fields.

The phone ignores commands older than 10 minutes (so a fresh cursor never replays an old send).
There are no send caps (owner's decision); every send is audited instead.

## Tamper-evident audit log
Both sides keep an append-only, hash-chained log of every send.
Entry hash = `hex(sha256(UTF-8(prev_hash + "\n" + join("\u001f", fields))))` with the fields in the
fixed order listed below, each rendered as a plain string (numbers in decimal, null/missing as empty string).
The first `prev_hash` is 64 zeros (`"0"*64`).
- Phone entry fields, in hash order: `seq, ts, id, agent, number, text, sim, result, error` (+ `prev`, `hash`).
  A `send_sms` result carries this entry in `audit`; delivery reports are appended as their own entries
  (`result` = `delivered` / `delivery_failed`). A command older than 10 min gets
  `result {"ok":false,"status":"failed","error":"expired"}` and an entry with `result` = `expired`.
  A repeated command id is never sent twice: the phone answers with the earlier result.
- PC (`phone.db` table `audit_log`) appends its own chained events (`queued`, `sent`, `failed`, `delivered`,
  `delivery_failed`, `expired`) and stores the phone's `hash` alongside, so the two chains can be cross-checked.
  `phone.py audit --verify` recomputes the PC chain; SQLite triggers reject UPDATE/DELETE on it.

## v3 additions (backward compatible: old peers ignore unknown fields; an old phone answers an unknown op with `ok:false`)
- `status` gains `"default_sim": 0|1|2` (0 = the phone's system default SMS SIM) and
  `"privacy": {"sms":bool,"contacts":bool,"calls":bool}` (categories the owner allows to sync; a disabled
  category is not uploaded and its backfill counts as complete). The PC stores `default_sim` from the latest
  status into `config.json` (`default_sim`, `null` for 0) — the phone is the source of truth; `phone.py sim N` remains a PC-side override until the next status.
  The phone applies its default SIM when a `send_sms` carries no `sim`.
- New down op `rules_mirror {"rules":[{"name","trigger","action","enabled":bool,"last_run":ms|null,"last_ok":bool|null,"runs":n}],"ts"}`:
  a read-only snapshot of the PC automation rules (`trigger` / `action` are short human summaries). The phone replaces
  its stored copy and replies `result {"ok":true}`. The PC daemon sends it at start, when `rules.yaml` changes, and at most
  once per 60 s after rule runs.
- Idempotency is PC-side: `send` accepts a client key; the same key within 24 h returns the first send's result with
  `"already_sent": true` instead of posting a new command. (The phone's own repeated-command-id guard is unchanged.)

### PC behaviour notes (v3, informative; no wire change)
- `default_sim` is written to `config.json` only when a `status` carries the key (a v2 status leaves a PC-side `phone.py sim N` override untouched).
- `rules_mirror` is posted as a normal `cmd` (`id`, `ts`, `op`); `ts` inside the envelope is the snapshot time. A post that fails (relay down) is retried on the next daemon loop. `last_run` is epoch ms of the last real (non-dry) firing.
- Idempotency keys live in `phone.db` table `idem` (claimed under a SQLite write transaction + primary key, so concurrent callers post at most one command). If the relay rejected the post, the same key later re-posts the SAME command id, relying on the phone's repeated-command-id guard. A key reused for a different number/text is refused. Only a final phone result (`status` `sent` or `failed`, incl. `error:"expired"`) is stored as "the first result"; until then `already_sent:true` comes with `status:"queued"`.
- PC-only audit events added in v3: `post_failed` (the relay refused the post; nothing stored) and `post_unknown` (no answer; maybe stored). The PC appends `queued` BEFORE posting. Idempotency keys are scoped per agent name (stored as `agent + "\x1f" + key`).
