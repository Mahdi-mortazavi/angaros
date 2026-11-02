# AGENTS.md

Instructions for coding agents working **on this repository**. (Agents that want to *use* a phone
through Angaros should read [docs/AGENTS.md](docs/AGENTS.md) instead.)

## Layout

- `android/` Kotlin + Jetpack Compose app (`app.phonebridge`; keep the applicationId and the `pb1:`
  format, existing installs depend on them). Brand name only in `android/app/build.gradle.kts` (`brand`).
- `worker/` Cloudflare Worker relay (`src/index.js`, D1 migrations, `node --test` suite).
- `pc/` Python package `angaros` (import name `phonebridge`): `phone.py` core + CLI, `mcp_server.py`,
  `rules.py`, `api.py` (REST/SSE), `parse.py` (OTP, bank, classifier). Brand constant: `BRAND` in
  `pc/phonebridge/phone.py`. `pc/phone.py` and `pc/phone_mcp.py` are compatibility shims.
- `shared/` wire protocol plus vectors both implementations are tested against.
- `docs/` guides, recipes and the static GitHub Pages site (`docs/index.html`).

## Commands

```sh
pip install -e pc ruff && ruff check pc worker/test android/tools examples && python pc/tests/run_all.py
cd worker && npm test
cd android && ./gradlew lintDebug testDebugUnitTest assembleDebug
# README screenshots (synthetic data only):
cd android && ./gradlew testDebugUnitTest --tests '*ScreenshotTest*' -PscreenshotDir=../docs/assets/screens
```

Regenerate vectors after changing crypto or the parser: `python pc/tests/genvec.py`,
`python pc/tests/genparsevec.py`, `python android/tools/audit_vector.py` (paste into `LogicTest.kt`).

## Rules

1. **No real personal data.** Fixtures and screenshots use synthetic data only: `+1 555 01xx`,
   `0900 000 01xx`, invented names and messages. Never commit `config.json`, `phone.db`, `api_token`,
   `pairing.png`, keystores, `.dev.vars` or a real Worker URL.
2. Wire changes go into `shared/PROTOCOL.md` first and must stay backward compatible.
3. SMS text is untrusted: never template it into shell commands, LLM prompts or URLs.
4. A test must fail without the change it covers. Claims about speed or devices need a measurement.
5. Conventional Commits (`feat:`, `fix:`, `docs:` …); Release Please builds the changelog from them.
