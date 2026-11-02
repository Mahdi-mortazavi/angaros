// Phone Bridge mailbox. Stores ciphertext only; knows nothing about what it carries.
const TTL_MS = 7 * 24 * 3600 * 1000;
const MAX_BLOB = 256 * 1024;
const DEV_PER_MIN = 120;
const BAD_AUTH_PER_MIN = 30;
const POLL_MS = 1500; // ponytail: D1 polling inside long-poll; Durable Object push if read volume ever matters

const json = (o, status = 200) =>
  new Response(JSON.stringify(o), { status, headers: { "content-type": "application/json" } });

async function hmacHex(secret, msg) {
  const k = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("HMAC", k, new TextEncoder().encode(msg));
  return [...new Uint8Array(sig)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function safeEqual(a, b) {
  if (a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}

async function authDevice(req, env) {
  const m = /^Bearer ([A-Za-z0-9_-]{16,64})\.([0-9a-f]{64})$/.exec(req.headers.get("authorization") || "");
  if (!m || !env.PAIR_MASTER) return null;
  return safeEqual(await hmacHex(env.PAIR_MASTER, m[1]), m[2]) ? m[1] : null;
}

async function overLimit(env, key, limit) {
  const win = Math.floor(Date.now() / 60000);
  const row = await env.DB.prepare(
    "INSERT INTO rl (k, win, n) VALUES (?1, ?2, 1) ON CONFLICT(k, win) DO UPDATE SET n = n + 1 RETURNING n",
  ).bind(key, win).first();
  return row.n > limit;
}

async function cleanup(env) {
  await env.DB.batch([
    env.DB.prepare("DELETE FROM msg WHERE ts < ?1").bind(Date.now() - TTL_MS),
    env.DB.prepare("DELETE FROM rl WHERE win < ?1").bind(Math.floor(Date.now() / 60000) - 2),
  ]);
}

async function handle(req, env, ctx) {
  const url = new URL(req.url);
  const m = /^\/v1\/(up|down)$/.exec(url.pathname);
  if (url.pathname === "/v1/health") return json({ ok: true });
  if (!m) return json({ error: "not found" }, 404);
  const box = m[1];

  const dev = await authDevice(req, env);
  if (!dev) {
    const ip = req.headers.get("cf-connecting-ip") || "local";
    if (await overLimit(env, "ip:" + ip, BAD_AUTH_PER_MIN)) return json({ error: "rate limited" }, 429);
    return json({ error: "unauthorized" }, 401);
  }
  if (await overLimit(env, "dev:" + dev, DEV_PER_MIN)) return json({ error: "rate limited" }, 429);

  if (req.method === "POST") {
    const text = await req.text();
    if (text.length > MAX_BLOB) return json({ error: "too large" }, 413);
    let blob;
    try { blob = JSON.parse(text).blob; } catch { /* fallthrough */ }
    if (typeof blob !== "string" || !/^[A-Za-z0-9+/=]+$/.test(blob)) return json({ error: "bad blob" }, 400);
    const row = await env.DB.prepare("INSERT INTO msg (dev, box, blob, ts) VALUES (?1, ?2, ?3, ?4) RETURNING id")
      .bind(dev, box, blob, Date.now()).first();
    if (Math.random() < 0.01) ctx.waitUntil(cleanup(env));
    return json({ id: row.id });
  }

  if (req.method === "GET") {
    const after = Math.max(0, parseInt(url.searchParams.get("after") || "0", 10) || 0);
    const wait = Math.min(25, Math.max(0, parseInt(url.searchParams.get("wait") || "0", 10) || 0));
    const limit = Math.min(200, Math.max(1, parseInt(url.searchParams.get("limit") || "100", 10) || 100));
    const deadline = Date.now() + wait * 1000;
    const q = env.DB.prepare(
      "SELECT id, blob FROM msg WHERE dev = ?1 AND box = ?2 AND id > ?3 AND ts >= ?4 ORDER BY id LIMIT ?5",
    );
    for (;;) {
      const { results } = await q.bind(dev, box, after, Date.now() - TTL_MS, limit).all();
      if (results.length || Date.now() >= deadline) {
        return json({ items: results, cursor: results.length ? results[results.length - 1].id : after });
      }
      await new Promise((r) => setTimeout(r, Math.min(POLL_MS, deadline - Date.now())));
    }
  }
  return json({ error: "method not allowed" }, 405);
}

export default {
  fetch: (req, env, ctx) => handle(req, env, ctx).catch(() => json({ error: "internal" }, 500)),
  scheduled: (_ev, env, ctx) => ctx.waitUntil(cleanup(env)),
};
