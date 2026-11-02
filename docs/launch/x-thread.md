# X / Twitter thread (draft)

Attach `docs/assets/social-preview.png` to post 1 and the four screens (`docs/assets/screens/`) to post 3.

1/
My AI agents could read my email, calendar and files. Not my phone.

So I built Angaros: your Android phone's SMS, OTP codes, contacts and call log for Claude, Hermes,
Codex, Cursor and any MCP client. End-to-end encrypted, open source.
github.com/Mahdi-mortazavi/angaros

2/
The relay is a Cloudflare Worker you deploy yourself. It's a ~100-line mailbox for AES-256-GCM
blobs. It can't read anything, and nothing stays longer than 7 days.
No open ports, no account, no third-party server.

3/
On the phone: connection health, a classified feed with one-tap OTP copy, and a screen listing
every SMS an agent sent in your name, with a tamper-evident record.
(Screens shown with synthetic data.)

4/
On the computer: 22 MCP tools, a JSON CLI, a Python SDK, REST + SSE, and YAML automations.
"What's the code my bank just sent?" → otp_wait → done.

5/
Anyone can text you, so SMS text is treated as untrusted input. Results are flagged, rules never
paste message text into shell commands or prompts, and sends need dry-run + idempotency keys.

6/
Honest status: verified on one phone (Samsung A30s, Android 16). SMS only, Android only, MIT.
Device reports welcome.

Angaros: Old Persian for the royal courier. The Greeks borrowed it as angelos: angel.
