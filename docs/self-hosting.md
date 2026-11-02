# Deploying your relay

The relay is a Cloudflare Worker with one D1 database. It stores encrypted blobs for at most seven
days and cannot read them. You deploy it once, to your own Cloudflare account.

## Requirements

- A Cloudflare account (the free plan is enough for one phone).
- Node.js 22+ (for `npx wrangler`).
- `angaros` installed on your computer: `pip install "git+https://github.com/Mahdi-mortazavi/angaros#subdirectory=pc"` (a PyPI release is planned).

## Steps

```sh
git clone https://github.com/Mahdi-mortazavi/angaros
cd angaros/worker
npx wrangler@4 login

# 1. Create the database and paste the printed database_id into wrangler.toml
npx wrangler@4 d1 create angaros

# 2. Create the tables
npx wrangler@4 d1 migrations apply angaros --remote

# 3. Set the pairing master secret (generated once and stored in your angaros config.json)
angaros master | npx wrangler@4 secret put PAIR_MASTER

# 4. Deploy
npx wrangler@4 deploy
```

`wrangler deploy` prints your relay URL, for example `https://angaros.<you>.workers.dev`. Check it:

```sh
curl https://angaros.<you>.workers.dev/v1/health      # {"ok":true}
angaros pair --worker https://angaros.<you>.workers.dev
```

## What it costs

By construction (not from a measured bill): the phone holds one long-poll open for up to 25 s at a
time, and so does the computer while `angaros daemon` runs. That is a few thousand requests a day
for one phone, far inside the free plan's daily request allowance. Each long-poll re-checks D1 every
1.5 s while it waits, which counts as D1 reads; that also stays well inside the free allowance for one
phone. Check your own dashboard after a day.

## Operating it

- **Rotate the master secret** to revoke every pairing at once:
  `angaros master` prints the current one; generate a new one by deleting `master` from
  `config.json`, run `angaros master | npx wrangler@4 secret put PAIR_MASTER`, then pair again.
- **Purge:** a cron trigger deletes items older than seven days every hour.
- **Rate limits:** 120 requests a minute per device, 30 failed authentications a minute per IP.
- **Custom domain:** add a route in `wrangler.toml` and set the new URL in the app (Settings →
  Relay address). The token and key stay the same.

## Running it locally (development)

```sh
cd worker
echo "PAIR_MASTER=any-local-secret" > .dev.vars
npx wrangler@4 d1 migrations apply angaros --local
npx wrangler@4 dev --port 8799
python test/e2e.py http://127.0.0.1:8799      # real Worker + real CLI + a simulated phone
npm test                                       # unit tests, no wrangler needed
```
