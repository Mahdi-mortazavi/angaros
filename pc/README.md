# angaros

**Your phone, for your AI agents.** The computer side of [Angaros](https://github.com/Mahdi-mortazavi/angaros):
a CLI, an MCP server, a Python SDK and a local REST + SSE API over your own Android phone's SMS,
contacts and call log, synced end-to-end encrypted through a relay you deploy yourself.

<!-- mcp-name: io.github.Mahdi-mortazavi/angaros -->

```sh
pip install angaros
angaros pair --worker https://angaros.<you>.workers.dev   # then scan the QR code in the app
angaros status
claude mcp add phone --scope user -- angaros-mcp
```

```python
from phonebridge import Phone

phone = Phone()
for m in phone.sms(since="1d")["messages"]:
    print(m["time"], m["number"], m["class"])      # message bodies are untrusted third-party text
print(phone.otp(within="10m"))
phone.send("+15550100", "On my way", agent="my-script", dry_run=True,
           idempotency_key="on-my-way-2026-11-02")
```

- Console scripts: `angaros` (JSON CLI; `angaros schema` lists every command) and `angaros-mcp`
  (MCP server, stdio or `--http` on 127.0.0.1). `angaros mcp` is the same server.
- Data directory: `~/.angaros` (override with `ANGAROS_HOME`).
- The import name is `phonebridge` for compatibility with earlier versions.

You also need the Android app and a relay: see the
[quick start](https://github.com/Mahdi-mortazavi/angaros#quick-start-60-seconds).
Security model: [SECURITY.md](https://github.com/Mahdi-mortazavi/angaros/blob/main/SECURITY.md). MIT licensed.
