# Troubleshooting

Start with the doctor. It checks the relay, the pairing, how recently the phone checked in, the
daemon heartbeat, the default SIM and your Python environment, and prints a fix for every problem:

```sh
angaros doctor --pretty
```

Exit code 1 means at least one check failed.

| Symptom | Likely cause | Fix |
|---|---|---|
| `code: not_paired` | no pairing on this computer | `angaros pair --worker https://angaros.<you>.workers.dev`, scan the QR in the app |
| `code: unreachable`, `sync_error` | relay down, offline, or blocked on this network | `curl <relay>/v1/health`; reads keep answering from the local database meanwhile |
| Phone "last seen" keeps growing | Android stopped the app | App → Home → *Background use* → allow (Unrestricted); on Samsung also remove it from *Sleeping apps* |
| Nothing after `am force-stop` or "Force stop" in settings | Android rule: a force-stopped app stays stopped | open the app once |
| `send` returns `status: queued` | phone offline; the command waits on the relay | do **not** resend; the phone sends it within 10 minutes of coming online, or reports `expired`. Ask again with the same `--idempotency-key`, or check `angaros audit 5` |
| `send` returns `status: unknown` | the relay did not answer; the command may have been stored | same as above: same key, or `audit`; never a new key |
| History incomplete in `status` | backfill still uploading | wait; `angaros status` shows the progress per kind |
| HTTP 401 from REST or MCP-over-HTTP | missing or wrong bearer token | `angaros token`; `angaros token --rotate` to replace it |
| A rule never fires | daemon not running, or the event is older than `max_event_age` (6 h) | `angaros daemon`; `angaros rules test <file>`; MCP `rules_list` shows `last_run` |
| App does not start after a reboot | not direct-boot aware | unlock the phone once |
| App will not update over an older build | different signing key | uninstall and pair again (see the note below) |

## Signing keys and updates

Android only installs an update signed with the same key as the installed app. Official releases
from this repository are signed with the project's release key. If you installed a build signed with
any other key (your own build, or a pre-release), uninstall it first; unpairing loses nothing on the
computer, and `angaros pair` creates a new pairing in seconds.

## Collecting diagnostics

- **App:** Settings → Diagnostics → share. The report contains states and counters, no message text
  and no keys.
- **Computer:** `angaros doctor`, `angaros status --pretty`.

Never post `config.json`, `api_token`, a `pb1:` pairing string, your relay URL or real message text
in an issue.
