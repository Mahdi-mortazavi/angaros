# Contributing to Angaros

Thanks for wanting to help. Angaros is three small programs that share one
contract, and most of the rules below exist to keep that contract honest.

## The layout

| Path | What | Language | Tests |
|---|---|---|---|
| [`android/`](android/) | the phone app | Kotlin, Jetpack Compose | `./gradlew testDebugUnitTest lintDebug` |
| [`worker/`](worker/) | the relay (Cloudflare Worker + D1) | JavaScript | `npm test` |
| [`pc/`](pc/) | CLI, MCP server, Python SDK, REST API, rules engine | Python ≥ 3.10 | `python pc/tests/run_all.py` |
| [`shared/`](shared/) | the wire protocol and the vectors both sides test against | — | used by both suites |
| [`docs/`](docs/) | guides, recipes and the GitHub Pages site | — | `pc/tests/test_docs.py` runs the recipes |

## Getting started

```sh
# Python side
python -m venv .venv && . .venv/bin/activate      # Windows: .venv\Scripts\activate
pip install -e "pc" ruff
python pc/tests/run_all.py
ruff check pc

# Relay
cd worker && npm test

# Android (JDK 17+, Android SDK with platform 37)
cd android && ./gradlew testDebugUnitTest lintDebug assembleDebug
```

Behind a filtered network, Robolectric can fetch its Android jars from a mirror:
`./gradlew testDebugUnitTest -ProbolectricRepo=https://maven.aliyun.com/repository/public`.

## Rules that keep the project trustworthy

1. **The protocol is a contract.** Any change to what crosses the wire goes into
   [`shared/PROTOCOL.md`](shared/PROTOCOL.md) first, stays backward compatible
   (old peers ignore unknown fields), and is covered on both sides. If you touch
   crypto, regenerate `shared/test-vector.json` with `python pc/tests/genvec.py`
   and keep the Kotlin test green.
2. **No real personal data, ever.** Fixtures, screenshots, logs and issues use
   synthetic data only: numbers from `+1 555 0100–0199` or `0900 000 01xx`,
   invented names, invented messages. Never paste a real SMS, a real
   `pb1:` pairing string, a `config.json`, or a Worker URL into an issue or PR.
3. **A test must be able to fail.** Before you write one, know what a failing
   run looks like, and check that it does fail without your change.
4. **Claims need evidence.** "Faster", "fixes X on device Y" or "works on
   Android Z" in a PR needs the measurement or the log. If you could not test
   on a device, say so; that is fine.
5. **SMS text is untrusted.** Code that puts message text anywhere near a shell,
   a prompt or a URL needs a reviewer's explicit attention.

## Pull requests

- One logical change per PR; describe what and why, and how you verified it
  (the PR template asks).
- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/)
  (`feat:`, `fix:`, `docs:` …). Release Please uses them to write the changelog
  and pick the next version.
- CI must be green: Android lint and unit tests, worker tests, Python tests on
  3.10–3.13, ruff, CodeQL.

## Device reports

The app has been verified on exactly one phone so far (see the README). If you
run it on another device, a [device report](https://github.com/Mahdi-mortazavi/angaros/issues/new?template=device_report.yml)
is one of the most useful contributions you can make, even if everything works.

## Translations

The app ships English and Persian (`android/app/src/main/res/values*/strings.xml`).
New languages are welcome; keep the brand name in `@string/brand` only.
