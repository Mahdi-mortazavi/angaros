# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/). From the first public release on,
entries are written by Release Please from Conventional Commit messages.

## [3.0.3] - 2026-11-02

First public, open-source release (MIT).

### Changed
- Messages feed: rows are grouped and formatted off the main thread. Measured on a
  Samsung A30s (Android 16), compiled with the baseline profile: janky frames while
  flinging the feed fell from 13.35 % (3.0.2) to 6.1-6.2 % (two 3.0.3 runs); p90
  frame time 53 ms to 34 ms. The < 5 % target is not reached yet.
- Cold start on the same device, compiled: median 609 ms and 535 ms in two runs
  (3.0.2: 681 ms).
- Python client packaged as `angaros` on PyPI (`pip install angaros`) with the
  console scripts `angaros` and `angaros-mcp`; the import name stays
  `phonebridge`, and `python pc/phone.py` keeps working.
- Default data directory is `~/.angaros` (override with `ANGAROS_HOME`, or the
  older `PHONE_BRIDGE_DIR`). A checkout that already keeps `config.json` in `pc/`
  keeps using it.
- Release APKs are signed with a dedicated release key in CI; local release
  builds without a key are unsigned instead of falling back to the debug key.

### Fixed
- `angaros` long-poll (`ping`, `send --wait`, `otp --wait`) no longer fails the
  whole command on one network reset while time is left before the deadline.

## [3.0.2] - 2026-11-02

### Added
- `doctor` command: relay, pairing, phone freshness, daemon heartbeat, default
  SIM and versions, each failure with a fix.

### Changed
- UI polish across Home, Messages, Sends and Settings.

## [3.0.1] - 2026-11-02

### Changed
- Renamed the app to **Angaros** (applicationId `app.phonebridge` unchanged, so
  updates install over older builds signed with the same key).
- English is the default language; Persian (RTL, Persian digits, Jalali dates)
  is one tap away in onboarding and settings.

### Fixed
- `versionCode` is derived from the version (`major*10000 + minor*100 + patch`);
  3.0.0 had shipped with a lower code than 2.1.0 and could not update it.

## [3.0.0] - 2026-11-02

### Added
- Material 3 Expressive app: Home dashboard, Messages, Sends (with audit-chain
  status), Automations (read-only mirror of PC rules), Settings with per-category
  privacy toggles, pause with undo, custom relay URL, default SIM.
- MCP server v2: 22 tools with output schemas and annotations, resources, HTTP
  transport on 127.0.0.1 with a bearer token.
- Python SDK, local REST API + SSE event stream (OpenAPI 3.1), `run` command.
- Rules engine: `on_event`, `cron`, `webhook`, `llm` actions; untrusted SMS text
  is never interpolated into shell commands or prompts.
- Per-agent idempotency keys (`already_sent`).
- Ten automation recipes.

## [2.1.0] - 2026-11-01

### Added
- End-to-end encrypted relay (Cloudflare Worker + D1), pairing by QR code.
- SMS, contacts and call-log sync with full backfill; sending with delivery
  reports; tamper-evident audit log on phone and computer.
- Iranian bank SMS parser, OTP extraction, Persian/Arabic-normalised search.

[3.0.3]: https://github.com/Mahdi-mortazavi/angaros/releases/tag/v3.0.3
