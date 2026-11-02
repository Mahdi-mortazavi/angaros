# Publish checklist (owner)

Everything below needs the owner's accounts or approval. Nothing here has been done yet.

## 1. Before the first push

- [ ] Read `docs/launch/REPORT_V4.md`, especially *Not verified* and *Decisions taken*.
- [ ] Re-run the gates locally from a clean clone:
      `python scripts/privacy_scan.py` · `pip install -e pc ruff && ruff check pc worker/test android/tools examples && python pc/tests/run_all.py` ·
      `cd worker && npm test` · `cd android && ./gradlew lintDebug testDebugUnitTest assembleDebug`
- [ ] Skim the four screenshots in `docs/assets/screens/` and the social preview once more yourself.
- [ ] `git init` was run but **nothing is committed**. Create the first commit on `main` (this is a fresh
      repository, so there is no PR to open yet), e.g. `git add -A && git commit -m "feat: initial open-source release"`.
      Check `git status --ignored` first: `android/local.properties` and build outputs must stay ignored.

## 2. GitHub repository (`Mahdi-mortazavi/angaros`)

- [ ] Create the empty public repo, add the remote, push `main`.
- [ ] About box: description, website and 20 topics from `docs/launch/TOPICS.md`.
- [ ] Settings → General → Social preview: upload `docs/assets/social-preview.png`.
- [ ] Settings → Pages → Source: **GitHub Actions** (then `pages.yml` deploys `docs/`).
- [ ] Settings → Code security: enable private vulnerability reporting, Dependabot alerts, secret scanning + push protection.
- [ ] Settings → Actions → General: allow GitHub Actions to create pull requests (needed by Release Please).
- [ ] Enable Discussions (linked from SUPPORT.md and the issue chooser).
- [ ] Branch protection on `main`: require the CI checks (Android, worker, Python 3.10–3.13, privacy scan, CodeQL).

## 3. Release signing (never the debug key)

- [ ] Create the release keystore **outside** the repo and back it up in two places. Losing it means users
      can never update in place again.
      `keytool -genkeypair -keystore angaros-release.jks -storetype PKCS12 -alias angaros -keyalg RSA -keysize 4096 -validity 10000`
- [ ] Repository secrets: `SIGNING_KEYSTORE_B64` (`base64 -w0 angaros-release.jks`), `SIGNING_KEY_ALIAS`,
      `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_PASSWORD`.
- [ ] For local signed builds: copy `android/keystore.properties.example` to `android/keystore.properties`.
- [ ] **Your own phone:** the installed 3.0.3 is signed with the old debug key. A release signed with the new key
      will not install over it. Uninstall, install the release, pair again (`angaros pair`). Your computer-side
      `phone.db` and audit log are kept.

## 4. First release

- [ ] Push tag `v3.0.3` (or run *Release* manually with `tag: v3.0.3`). Check the release has
      `Angaros-3.0.3-{universal,arm64-v8a,armeabi-v7a}.apk`, the wheel, the sdist and `SHA256SUMS`, and that
      `gh attestation verify` passes for one APK.
- [ ] Install the published universal APK on a phone and pair it once: this is the first end-to-end check of an
      artifact built by CI.
- [ ] From then on, merge Conventional-Commit PRs; Release Please opens the release PR.

## 5. PyPI and the MCP registry

- [ ] PyPI: the name `angaros` was free on 2026-11-02 (`https://pypi.org/pypi/angaros/json` → 404). Create the
      project with a **trusted publisher** (repo `Mahdi-mortazavi/angaros`, workflow `release.yml`, environment
      `pypi`), then set the repository variable `PUBLISH_PYPI=true` and re-run the release.
- [ ] MCP registry: `mcp-publisher login github` then `mcp-publisher publish` with `server.json`. Validate first;
      `server.json` was written against the 2025-09-29 schema and has **not** been validated with `mcp-publisher`.
      The PyPI README (`pc/README.md`) carries the required `mcp-name:` marker.

## 6. Relay

- [ ] Your existing Worker (`phone-bridge`) keeps working; the tree's `wrangler.toml` names a new Worker `angaros`
      with a placeholder D1 id. Do not commit your real `database_id`.
- [ ] Optional: verify the quick-start commands in `README.md` once on a fresh Cloudflare account.

## 7. Launch (drafts in `docs/launch/`)

- [ ] Show HN (`show-hn.md`), Reddit (`reddit.md`, check each subreddit's rules), X (`x-thread.md`),
      Telegram (`telegram-fa.md`), dev.to (`devto.md`, `published: false`).
- [ ] Post only after the release assets and the Pages site are live.
